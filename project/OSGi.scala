/*
 * Copyright 2015-2024 The Developers Team.
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
import com.typesafe.sbt.osgi.SbtOsgi._

/**
  * An object with settings related to OSGi.
  */
object OSGi {
  /**
    * Returns default settings for OSGi bundles.
    *
    * @return default OSGi settings
    */
  def osgiSettings: Seq[sbt.Setting[_]] = defaultOsgiSettings ++ Seq(
    OsgiKeys.requireCapability := "osgi.ee;filter:=\"(&(osgi.ee=JavaSE)(version>=1.8))\""
  )
}
