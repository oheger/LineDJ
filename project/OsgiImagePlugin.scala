/*
 * Copyright 2015-2019 The Developers Team.
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

import java.util.jar.{Attributes, JarFile}

import com.typesafe.sbt.osgi.OsgiKeys
import sbt.Keys._
import sbt.librarymanagement.{DependencyFilter, ModuleFilter}
import sbt.{Def, _}

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
  *  - Exclusions for dependencies not be added to the image can be specified.
  *  - The sub output directory in which to write the collected dependency
  * bundles can be provided.
  *  - The sub folder to the OSGi image template can be specified.
  *  - The root path in which all OSGi image templates are located is defined
  * by the ''osgi.image.rootPath'' system property.  
  */
object OsgiImagePlugin extends AutoPlugin {
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
      * The main task for creating an OSGi image. Call this task in a project
      * in order to generate an image for this application.
      */
    lazy val osgiImage = taskKey[Unit]("Generates an OSGi image for the current project")

    lazy val baseOsgiImageSettings: Seq[Def.Setting[_]] = Seq(
      excludedModules := Nil,
      osgiImage := {
        val dependencies = update.value.matching(createDependenciesFilter(excludedModules.value))
        val projectFiles = fetchDependentProjectFiles().value
        buildOsgiImage(dependencies, projectFiles)
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
    val artifacts = artifactFilter(`type` = "jar")
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
    * Creates a dynamic task to obtain the OSGi bundles for the projects the
    * current project depends on. Using a dynamic task is needed; otherwise the
    * results of the ''osgiBundle'' task from referenced projects cannot be
    * obtained.
    *
    * @return a task for obtaining bundle jars of dependent projects
    */
  private def fetchDependentProjectFiles(): Def.Initialize[Task[Seq[sbt.File]]] = Def.taskDyn {
    val projectsDependencies = sbt.Keys.buildDependencies.value.classpathTransitive
    val currentProjectDependencies = projectsDependencies.getOrElse(thisProjectRef.value, Nil)
    val filter = ScopeFilter(inProjects(currentProjectDependencies: _*))
    OsgiKeys.bundle.all(filter)
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
    */
  private def buildOsgiImage(dependencies: Seq[File], projects: Seq[File]): Unit = {
    //TODO implement image creation logic
    println("Building OSGi image for dependencies: " + dependencies.filter(isBundle))
    println("Project dependencies: " + projects)
  }
}
