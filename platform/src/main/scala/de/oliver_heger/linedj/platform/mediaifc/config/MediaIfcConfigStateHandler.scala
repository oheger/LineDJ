/*
 * Copyright 2015-2021 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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

package de.oliver_heger.linedj.platform.mediaifc.config

/**
  * A trait for receiving notifications about the current availability state of
  * the configuration for the media archive interface.
  *
  * An object implementing this trait can be passed to an
  * [[OpenMediaIfcConfigTask]] object. Its state is set based on the
  * availability of the configuration. This is a generic way to adapt the UI of
  * an application accordingly; for instance, an action could be disabled if
  * there is no configuration option available.
  */
trait MediaIfcConfigStateHandler {
  /**
    * Updates the availability state of the configuration for the media archive
    * interface. If invoked with '''true''', a configuration is supported;
    * otherwise, this method is called with the argument '''false'''.
    *
    * @param configAvailable flag whether configuration is supported
    */
  def updateState(configAvailable: Boolean)
}
