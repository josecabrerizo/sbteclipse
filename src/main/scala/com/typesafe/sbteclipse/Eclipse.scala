/*
 * Copyright 2011 Typesafe Inc.
 *
 * This work is based on the original contribution of WeigleWilczek.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.typesafe.sbteclipse

import EclipsePlugin.{ ClasspathEntry, EclipseExecutionEnvironment, EclipseKeys }
import java.io.FileWriter
import java.util.Properties
import sbt.{ Command, Configuration, Configurations, File, IO, Keys, Project, ProjectRef, ResolvedProject, State, ThisBuild, richFile }
import sbt.CommandSupport.logger
import scala.collection.JavaConverters
import scala.xml.{ Elem, NodeSeq, PrettyPrinter }
import scalaz.{ Failure, Success }
import scalaz.Scalaz._

private object Eclipse {

  val SettingFormat = """-?([^:]*):?(.*)""".r

  val FileSep = System.getProperty("file.separator")

  def eclipseCommand(
    executionEnvironment: Option[EclipseExecutionEnvironment.Value],
    skipParents: Boolean,
    withSource: Boolean,
    classpathEntryCollector: PartialFunction[ClasspathEntry, ClasspathEntry],
    commandName: String): Command =
    Command(commandName)(_ => parser)((state, args) =>
      action(executionEnvironment, skipParents, withSource, classpathEntryCollector, args.toMap)(state)
    )

  def parser = {
    import EclipseOpts._
    (boolOpt(ExecutionEnvironment) | boolOpt(SkipParents) | boolOpt(WithSource)).*
  }

  def action(
    executionEnvironment: Option[EclipseExecutionEnvironment.Value],
    skipParents: Boolean,
    withSource: Boolean,
    classpathEntryCollector: PartialFunction[ClasspathEntry, ClasspathEntry],
    args: Map[String, Boolean])(
      implicit state: State) = {

    logger(state).info("About to create Eclipse project files for your project(s).")
    import EclipseOpts._
    val ee = args get ExecutionEnvironment getOrElse executionEnvironment
    val sp = args get SkipParents getOrElse skipParents
    val ws = args get WithSource getOrElse withSource

    contentsForAllProjects(sp, classpathEntryCollector).fold(onFailure, onSuccess)
  }

  def contentsForAllProjects(
    skipParents: Boolean,
    classpathEntryCollector: PartialFunction[ClasspathEntry, ClasspathEntry])(
      implicit state: State) = {
    val contents = for {
      ref <- structure.allProjectRefs
      project <- Project.getProject(ref, structure) if project.aggregate.isEmpty || !skipParents
    } yield {
      val configs = configurations(ref)
      (name(ref) |@|
        buildDirectory(ref) |@|
        baseDirectory(ref) |@|
        mapConfigs(configs, srcDirectories(ref)) |@|
        target(ref) |@|
        scalacOptions(ref) |@|
        mapConfigs(configs, externalDependencies(ref)) |@|
        mapConfigs(configs, projectDependencies(ref, project)))(content(classpathEntryCollector))
    }
    contents.sequence[ValidationNELS, Content]
  }

  def onFailure(errors: NELS)(implicit state: State) = {
    logger(state).error("Could not create Eclipse project files: %s" format (errors.list mkString ", "))
    state.fail
  }

  def onSuccess(contents: Seq[Content])(implicit state: State) = {
    if (contents.isEmpty)
      logger(state).warn("There was no project to create Eclipse project files for!")
    else {
      val names = contents map writeContent
      logger(state).info("Successfully created Eclipse project files for project(s): %s" format (names mkString ", "))
    }
    state
  }

  def mapConfigs[A](configurations: Seq[Configuration], f: Configuration => ValidationNELS[Seq[A]]) =
    (configurations map f).sequence map (_.flatten.distinct)

  def content(
    classpathEntryCollector: PartialFunction[ClasspathEntry, ClasspathEntry])(
      name: String,
      buildDirectory: File,
      baseDirectory: File,
      srcDirectories: Seq[(File, File)],
      target: File,
      scalacOptions: Seq[(String, String)],
      externalDependencies: Seq[File],
      projectDependencies: Seq[String])(
        implicit state: State) =
    Content(
      name,
      baseDirectory,
      projectXml(name),
      classpath(
        classpathEntryCollector,
        buildDirectory,
        baseDirectory,
        srcDirectories,
        target,
        externalDependencies,
        projectDependencies
      ),
      scalacOptions)

  def projectXml(name: String) =
    <projectDescription>
      <name>{ name }</name>
      <buildSpec>
        <buildCommand>
          <name>org.scala-ide.sdt.core.scalabuilder</name>
        </buildCommand>
      </buildSpec>
      <natures>
        <nature>org.scala-ide.sdt.core.scalanature</nature>
        <nature>org.eclipse.jdt.core.javanature</nature>
      </natures>
    </projectDescription>

  def classpath(
    classpathEntryCollector: PartialFunction[ClasspathEntry, ClasspathEntry],
    buildDirectory: File,
    baseDirectory: File,
    srcDirectories: Seq[(File, File)],
    target: File,
    externalDependencies: Seq[File],
    projectDependencies: Seq[String])(
      implicit state: State) = {
    val entries = Seq(
      (for ((dir, output) <- srcDirectories) yield srcEntry(baseDirectory, output)(dir)).flatten,
      externalDependencies map libEntry(buildDirectory, baseDirectory),
      projectDependencies map ClasspathEntry.Project,
      Seq("org.eclipse.jdt.launching.JRE_CONTAINER") map ClasspathEntry.Con, // TODO Optionally use execution env!
      Seq(output(baseDirectory, target)) map ClasspathEntry.Output
    ).flatten collect classpathEntryCollector map (_.toXml)
    <classpath>{ entries }</classpath>
  }

  def srcEntry(baseDirectory: File, classDirectory: File)(srcDirectory: File)(implicit state: State) = {
    if (srcDirectory.exists()) {
      logger(state).debug("Creating src entry for directory '%s'." format srcDirectory)
      Some(ClasspathEntry.Src(relativize(baseDirectory, srcDirectory), output(baseDirectory, classDirectory)))
    } else {
      logger(state).debug("Skipping src entry for not-existing directory '%s'." format srcDirectory)
      None
    }
  }

  def libEntry(buildDirectory: File, baseDirectory: File)(file: File)(implicit state: State) = {
    val path =
      (IO.relativize(buildDirectory, baseDirectory) |@| IO.relativize(buildDirectory, file))((buildToBase, buildToFile) =>
        "%s/%s".format(buildToBase split FileSep map (_ => "..") mkString FileSep, buildToFile)
      ) getOrElse file.getAbsolutePath
    ClasspathEntry.Lib(path)
  }

  // Getting and transforming settings and task results

  def name(ref: ProjectRef)(implicit state: State) =
    setting(Keys.name, ref, Configurations.Default)

  def buildDirectory(ref: ProjectRef)(implicit state: State) =
    setting(Keys.baseDirectory, ThisBuild, Configurations.Default)

  def baseDirectory(ref: ProjectRef)(implicit state: State) =
    setting(Keys.baseDirectory, ref, Configurations.Default)

  def configurations(ref: ProjectRef)(implicit state: State) =
    setting(EclipseKeys.configurations, ref, Configurations.Default).fold(
      _ => Seq(Configurations.Compile, Configurations.Test),
      _.toSeq
    )

  def target(ref: ProjectRef)(implicit state: State) =
    setting(Keys.target, ref, Configurations.Default)

  def srcDirectories(ref: ProjectRef)(configuration: Configuration)(implicit state: State) = {
    (setting(Keys.sourceDirectories, ref, configuration) |@|
      setting(Keys.resourceDirectories, ref, configuration) |@|
      setting(Keys.classDirectory, ref, configuration))(srcDirsToOutput)
  }

  def scalacOptions(ref: ProjectRef)(implicit state: State) =
    evaluateTask(Keys.scalacOptions, ref, Configurations.Default) map { options =>
      def values(value: String) =
        value split "," map (_.trim) filterNot (_ contains "org.scala-lang.plugins/continuations")
      options collect {
        case SettingFormat(key, value) if key == "Xplugin" && !values(value).isEmpty =>
          key -> (values(value) mkString ",")
        case SettingFormat(key, value) if key != "Xplugin" =>
          key -> (if (!value.isEmpty) value else "true")
      } match {
        case Nil => Nil
        case os => ("scala.compiler.useProjectSettings" -> "true") +: os
      }
    }

  def externalDependencies(ref: ProjectRef)(configuration: Configuration)(implicit state: State) =
    evaluateTask(Keys.externalDependencyClasspath, ref, configuration) map (_.files)

  def projectDependencies(ref: ProjectRef, project: ResolvedProject)(configuration: Configuration)(implicit state: State) = {
    val projectDependencies = project.dependencies collect {
      case dependency if dependency.configuration map (_ == configuration) getOrElse true =>
        setting(Keys.name, dependency.project)
    }
    projectDependencies.sequence
  }

  // Writing to disk

  def writeContent(contents: Content): String = {
    saveXml(contents.dir / ".project", contents.project)
    saveXml(contents.dir / ".classpath", contents.classpath)
    if (!contents.scalacOptions.isEmpty)
      saveProperties(contents.dir / ".settings" / "org.scala-ide.sdt.core.prefs", contents.scalacOptions)
    contents.name
  }

  def saveXml(file: File, xml: Elem) = {
    val out = new FileWriter(file)
    try out.write(new PrettyPrinter(999, 2) format xml)
    finally if (out != null) out.close()
  }

  private def saveProperties(file: File, settings: Seq[(String, String)]): Unit = {
    file.getParentFile.mkdirs()
    val out = new FileWriter(file)
    val properties = new Properties
    for ((key, value) <- settings) properties.setProperty(key, value)
    try properties.store(out, "Generated by sbteclipse")
    finally if (out != null) out.close()
  }

  // Utilities

  def srcDirsToOutput(sourceDirectories: Seq[File], resourceDirectories: Seq[File], output: File) =
    (sourceDirectories ++ resourceDirectories) map (_ -> output)

  def relativize(baseDirectory: File, file: File) = IO.relativize(baseDirectory, file).get

  def output(baseDirectory: File, classDirectory: File) = relativize(baseDirectory, classDirectory)
}

private object EclipseOpts {

  val ExecutionEnvironment = "execution-environment"

  val SkipParents = "skip-parents"

  val WithSource = "with-source"
}

private case class Content(
  name: String,
  dir: File,
  project: Elem,
  classpath: Elem,
  scalacOptions: Seq[(String, String)])