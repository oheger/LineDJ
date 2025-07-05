/*
 * Copyright 2015-2025 The Developers Team.
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

import OsgiImagePlugin.autoImport.module
import com.typesafe.sbt.osgi.OsgiKeys
import com.typesafe.sbt.osgi.SbtOsgi.*

/**
  * An object with settings related to OSGi.
  */
object OSGi {
  /**
    * A sequence with module definitions that should be excluded from all OSGi
    * images. These are typically valid OSGi bundles with metadata that is not
    * compatible with the target environment. Therefore, replacement bundles have
    * been created that are deployed instead of these artifacts.
    */
  final val DefaultExcludedModules: Seq[OsgiImagePlugin.ModuleDesc] = Seq(
    module(name = "jackson-module-scala_3"),
    module(name = "jguiraffe-java-fx"),
    module(organization = "org.osgi"),
    module(organization = "org.parboiled"),
    module(name = "scala-library"),
    module(name = "scala-reflect")
  )

  /**
    * The import declaration for Scala packages. Here, the Scala 3 version has
    * to be set explicitly; otherwise, version 2.13 is assumed.
    */
  final val ScalaImport: String = "scala.*;version=\"[3.3,4)\""

  /**
    * Returns default settings for OSGi bundles.
    *
    * @return default OSGi settings
    */
  def osgiSettings: Seq[sbt.Setting[?]] = defaultOsgiSettings ++ Seq(
    OsgiKeys.requireCapability := "osgi.ee;filter:=\"(&(osgi.ee=JavaSE)(version>=1.8))\"",
    OsgiKeys.importPackage := Seq(ScalaImport, "*")
  )
}
