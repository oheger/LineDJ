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

import com.typesafe.sbt.osgi.OsgiKeys
import sbt.Keys._
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

  object autoImport {
    /**
      * The main task for creating an OSGi image. Call this task in a project
      * in order to generate an image for this application.
      */
    lazy val osgiImage = taskKey[Unit]("Generates an OSGi image for the current project")

    lazy val baseOsgiImageSettings: Seq[Def.Setting[_]] = Seq(
      osgiImage := {
        val configs = configurationFilter("compile")
        val artifacts = artifactFilter(`type` = "jar")
        val projectFiles = fetchDependentProjectFiles().value
        buildOsgiImage(update.value.matching(configs && artifacts), projectFiles)
      }
    )
  }

  import autoImport._

  override def trigger: PluginTrigger = noTrigger

  override def requires: Plugins = empty

  override def projectSettings: Seq[Def.Setting[_]] = baseOsgiImageSettings

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
    * The main method for creating an OSGi image. The method is passed 
    * collections with the files to be added to the image. It then creates the
    * image structure in the output directory.
    *
    * @param dependencies the 3rd party dependencies to be included
    * @param projects     the inter-project dependencies to be included
    */
  private def buildOsgiImage(dependencies: Seq[File], projects: Seq[File]): Unit = {
    //TODO implement image creation logic
    println("Building OSGi image for dependencies: " + dependencies)
    println("Project dependencies: " + projects)
  }
}
