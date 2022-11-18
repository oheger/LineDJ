/*
 * Copyright 2015-2022 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.typesafe.sbt.osgi.OsgiKeys
import sbt.Keys._
import sbt.internal.util.ManagedLogger
import sbt.librarymanagement.{DependencyFilter, ModuleFilter}
import sbt.{Def, _}

import java.util.jar.{Attributes, JarFile}
import scala.language.postfixOps

/**
  * A project- internally plugin that supports the creation of OSGi images.
  *
  * LineDJ mainly provides building blocks that can be plugged together to 
  * create applications in the audio domain. The project has defined a number
  * of such applications. However, in order to run an application, typically a
  * number of dependencies have to be collected and deployed into an OSGi 
  * container. This is an error-prone task.
  *
  * This plugin supports the creation of OSGi deployments. It can be added to
  * sub projects defining the dependencies of specific applications. It then
  * collects all dependencies that are OSGi bundles and writes them into a file
  * structure, a so-called OSGi image. The image adheres to the layout of a
  * concrete OSGi container. When creating an image for a specific application
  * a template directory can be specified. All files in this directory and its
  * sub folders are then copied to the plugin's output directory. Then the
  * collected project dependencies are copied into a configurable directory in
  * this structure. (For instance, when using Apache Felix as OSGi container
  * this would be the ''bundle'' sub directory.)
  *
  * The plugin supports some configuration settings to fine-tune the creation
  * of OSGi images:
  *  - Exclusions for dependencies not to be added to the image can be
  *    specified.
  *  - The sub output directory in which to write the collected dependency
  *    bundles can be provided.
  *  - Sub folders to the OSGi image templates can be specified. These folders
  *    are copied into the generated image.
  *  - The root path in which all OSGi image templates are located is defined
  *    by the ''osgi.image.rootPath'' system property.
  */
object OsgiImagePlugin extends AutoPlugin {
  /** The system property that defines the root path of source OSGi images. */
  val PropImageRoot = "osgi.image.rootPath"

  /**
    * Name of the directory in the project's target folder in which the image
    * is generated.
    */
  val ImageTargetDirectory = "image"

  /**
    * Default name of the directory which stores OSGi bundles in the generated
    * OSGi image.
    */
  val DefaultBundleDirectory = "bundle"

  /** Constant for the bundle version manifest attribute. */
  private val AttrBundleVersion = new Attributes.Name("Bundle-Version")

  /**
    * A data class describing a module. This is used to exclude specific 
    * modules from the 3rd party dependencies. From the given organization and
    * name specs a module filter is constructed.
    *
    * @param orgSpec  optional filter spec for organizations
    * @param nameSpec optional filter spec for names
    */
  case class ModuleDesc(orgSpec: Option[String], nameSpec: Option[String]) {
    def filter: ModuleFilter = {
      (for {
        org <- orgSpec
        n <- nameSpec
      } yield (org, n)) match {
        case Some((orgFilter, nameFilter)) => moduleFilter(organization = orgFilter, name = nameFilter)
        case None =>
          orgSpec.map(org => moduleFilter(org))
            .getOrElse(nameSpec.map(n => moduleFilter(name = n)).getOrElse(moduleFilter()))
      }
    }
  }

  object autoImport {
    /** Setting to exclude modules from 3rd party dependencies. */
    lazy val excludedModules = settingKey[Seq[ModuleDesc]]("Defines the modules to be excluded")

    /**
      * Setting with the relative path to the bundle directory in a generated
      * OSGi image.
      */
    lazy val bundleDir = settingKey[String]("Path to the bundle directory in the OSGi image")

    /**
      * Setting that defines the source images to be copied. This is a sequence
      * of relative paths to the root directory with OSGi images as defined by
      * the ''osgi.image.rootPath'' system property. The directory structures
      * below these paths are copied into the target image directory in the
      * order they have been specified. This supports a hierarchical approach
      * in defining OSGi images: a generic base image can contain all common
      * files, specialized sub images have some more files.
      */
    lazy val sourceImagePaths = settingKey[Seq[String]]("Paths to source images to be copied")

    /**
      * The main task for creating an OSGi image. Call this task in a project
      * in order to generate an image for this application.
      */
    lazy val osgiImage = taskKey[Unit]("Generates an OSGi image for the current project")

    lazy val baseOsgiImageSettings: Seq[Def.Setting[_]] = Seq(
      excludedModules := Nil,
      bundleDir := DefaultBundleDirectory,
      sourceImagePaths := Nil,
      osgiImage := {
        val dependencies = update.value.matching(createDependenciesFilter(excludedModules.value))
        runTaskOnAffectedProjects(OsgiKeys.bundle).value
        runTaskOnAffectedProjects(publishLocal).value
        val projectFiles = runTaskOnAffectedProjects(sbt.Keys.packagedArtifacts).value
          .map(findBundleArtifact)
          .filter(_.isDefined)
          .map(_.get)
        buildOsgiImage(dependencies, projectFiles, target.value, bundleDir.value, sourceImagePaths.value,
          streams.value.log)
      }
    )

    /**
      * Convenience function to generate a ''ModuleDesc'' object.
      *
      * @param organization the organization filter of the module
      * @param name         the name filter of the module
      * @return the resulting ''ModuleDesc''
      */
    def module(organization: String = "", name: String = ""): ModuleDesc = {
      def optString(s: String): Option[String] =
        if (s.isEmpty) None else Some(s)

      ModuleDesc(optString(organization), optString(name))
    }
  }

  import autoImport._

  override def trigger: PluginTrigger = noTrigger

  override def requires: Plugins = empty

  override def projectSettings: Seq[Def.Setting[_]] = baseOsgiImageSettings

  /**
    * Creates a filter for the 3rd party dependencies to be added to the
    * generated OSGi image.
    *
    * @param exclusions the sequence with modules to be excluded
    * @return the resulting dependencies filter
    */
  private def createDependenciesFilter(exclusions: Seq[ModuleDesc]): DependencyFilter = {
    val configs = configurationFilter("compile")
    val artifacts = artifactFilter(`type` = "jar" | "bundle")
    val modules = createModuleFilter(exclusions)
    configs && artifacts && modules
  }

  /**
    * Creates a filter based on the modules to be excluded.
    *
    * @param exclusions the sequence with modules to be excluded
    * @return the resulting module filter
    */
  private def createModuleFilter(exclusions: Seq[ModuleDesc]): ModuleFilter =
    exclusions.foldLeft(moduleFilter()) { (filter, module) => filter - module.filter }

  /**
    * Creates a dynamic task that runs the given task on the projects the
    * current project depends on. The results produced by this task execution
    * are returned. Using a dynamic task is needed; otherwise the results of
    * the task from referenced projects cannot be obtained.
    *
    * @param task the task from which artifacts should be retrieved
    * @tparam T the result type of the task to execute
    * @return a task for obtaining bundle jars of dependent projects
    */
  private def runTaskOnAffectedProjects[T](task: TaskKey[T]): Def.Initialize[Task[Seq[Option[T]]]] = Def.taskDyn {
    val projectsDependencies = sbt.Keys.buildDependencies.value.classpathTransitive
    val currentProjectDependencies = projectsDependencies.getOrElse(thisProjectRef.value, Nil)
    val filter = ScopeFilter(inProjects(currentProjectDependencies: _*))
    (task ?).all(filter)
  }

  /**
    * Tries to determine the file associated with the OSGi bundle artifact from
    * the given optional map. Per default, this is the jar artifact. In case of
    * a project processed by SpiFly, there is another jar artifact with the
    * "spifly" classifier.
    *
    * @param optArtifacts an optional map with published artifacts
    * @return an option with the extracted artifact file
    */
  private def findBundleArtifact(optArtifacts: Option[Map[Artifact, File]]): Option[File] =
    optArtifacts flatMap { artifactMap =>
      val jarArtifacts = artifactMap.filter(e => e._1.`type` == "jar" && e._1.extension == "jar")
      jarArtifacts.size match {
        case 1 => jarArtifacts.get(jarArtifacts.keys.head)
        case 2 => jarArtifacts.find(e => e._1.classifier.contains("spifly")).map(_._2)
        case _ => None
      }
    }

  /**
    * Checks whether a file is a valid OSGi bundle. Only such files are copied 
    * to the resulting image.
    *
    * @param file the file to be examined
    * @return a flag whether this file is an OSGi bundle
    */
  private def isBundle(file: File): Boolean = {
    val jarFile = new JarFile(file)
    try {
      val manifest = jarFile.getManifest
      manifest.getMainAttributes.containsKey(AttrBundleVersion)
    } finally {
      jarFile.close()
    }
  }

  /**
    * The main method for creating an OSGi image. The method is passed 
    * collections with the files to be added to the image. It then creates the
    * image structure in the output directory.
    *
    * @param dependencies the 3rd party dependencies to be included
    * @param projects     the inter-project dependencies to be included
    * @param targetPath   the target directory of the current project
    * @param bundlePath   the relative bundle directory in the image
    * @param sourcePaths  the paths to the source OSGi images
    * @param log          the logger
    */
  private def buildOsgiImage(dependencies: Seq[File], projects: Seq[File], targetPath: File,
                             bundlePath: String, sourcePaths: Seq[String], log: ManagedLogger): Unit = {
    val imageDir = new File(targetPath, ImageTargetDirectory)
    log.info("Generating OSGi image under " + imageDir)

    createBundleDir(dependencies, projects, bundlePath, imageDir, log)
    copySourceImages(sourcePaths, imageDir, log)
  }

  /**
    * Generates the directory containing all OSGi bundles.
    *
    * @param dependencies the 3rd party dependencies to be included
    * @param projects     the inter-project dependencies to be included
    * @param bundlePath   the relative bundle directory in the image
    * @param imageDir     the path where to generate the image
    * @param log          the logger
    */
  private def createBundleDir(dependencies: Seq[File], projects: Seq[File], bundlePath: String,
                              imageDir: File, log: ManagedLogger): Unit = {
    val bundleDir = new File(imageDir, bundlePath)
    val (dependencyBundles, nonBundles) = dependencies.partition(isBundle)
    val bundleFiles = dependencyBundles ++ projects
    val bundleMapping = bundleFiles pair Path.flat(bundleDir)
    IO.copy(bundleMapping, CopyOptions(overwrite = true, preserveLastModified = true, preserveExecutable = false))

    if (nonBundles.nonEmpty) {
      log.warn("Found dependencies that are no bundles:")
      nonBundles.map(_.name).sorted.foreach(file => log.info("- " + file))
    }
  }

  /**
    * Copies all defined source OSGi images into the target image structure.
    *
    * @param sourcePaths the paths to the source OSGi images
    * @param imageDir    the path where to generate the image
    * @param log         the logger
    */
  private def copySourceImages(sourcePaths: Seq[String], imageDir: File, log: ManagedLogger): Unit = {
    lazy val imageRoot = file(System.getProperty(PropImageRoot, "."))
    lazy val copyOptions = CopyOptions(overwrite = true, preserveLastModified = true, preserveExecutable = true)
    sourcePaths foreach { sourcePath =>
      val sourceImage = new File(imageRoot, sourcePath)
      if (sourceImage.isDirectory) {
        log.info("Copying source image: " + sourceImage)
        IO.copyDirectory(sourceImage, imageDir,
          copyOptions)
      } else {
        log.info("Skipping source image as it does not exist: " + sourceImage)
      }
    }
  }
}
