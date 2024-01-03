/*
 * Copyright 2015-2024 The Developers Team.
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

import net.sf.jguiraffe.gui.builder.components.ComponentBuilderData
import net.sf.jguiraffe.gui.builder.enablers.ElementEnabler

/**
  * An implementation of [[MediaIfcConfigStateHandler]] that delegates to an
  * [[net.sf.jguiraffe.gui.builder.enablers.ElementEnabler]].
  *
  * An instance is passed an ''ElementEnabler'' at construction time.
  * Depending on the availability of a configuration, the enabler is enabled or
  * disabled.
  *
  * @param enabler     the ''ElementEnabler''
  * @param builderData the ''ComponentBuilderData''
  */
class EnablerConfigStateHandler(val enabler: ElementEnabler,
                                val builderData: ComponentBuilderData) extends
  MediaIfcConfigStateHandler:

  /**
    * @inheritdoc This implementation calls the ''ElementEnabler'' with the
    *             availability state of the configuration.
    */
  override def updateState(configAvailable: Boolean): Unit =
    enabler.setEnabledState(builderData, configAvailable)
