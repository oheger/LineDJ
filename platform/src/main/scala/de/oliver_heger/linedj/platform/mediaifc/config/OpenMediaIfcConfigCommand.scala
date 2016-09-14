/*
 * Copyright 2015-2016 The Developers Team.
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

import net.sf.jguiraffe.di.ClassLoaderProvider
import net.sf.jguiraffe.gui.app.{ApplicationBuilderData, OpenWindowCommand}
import net.sf.jguiraffe.resources.{ResourceLoader, ResourceManager}

/**
  * A special command class for opening a dialog window for the configuration
  * of the interface to the media archive.
  *
  * This command class inherits most of the functionality from its base class.
  * In addition, it ensures that the class loader for classes of the
  * configuration bundle is added to the ''ClassLoaderProvider'' and removed
  * later.
  *
  * @param configData the configuration data to be processed
  */
class OpenMediaIfcConfigCommand(val configData: MediaIfcConfigData)
  extends OpenWindowCommand(configData.configScriptLocator) {
  /** Stores the current class loader provider. */
  private var classLoaderProvider: ClassLoaderProvider = _

  /** The resource manager. */
  private var resourceManager: ResourceManager = _

  /** The original resource loader. */
  private var originalResourceLoader: ResourceLoader = _

  /**
    * @inheritdoc This implementation registers the class loader for the
    *             configuration dialog.
    */
  override protected[config] def prepareBuilderData(builderData: ApplicationBuilderData): Unit = {
    super.prepareBuilderData(builderData)
    classLoaderProvider = builderData.getParentContext.getClassLoaderProvider
    classLoaderProvider.registerClassLoader(MediaIfcConfigData.ConfigClassLoaderName,
      configData.configClassLoader)
    //TODO This is a hack until JGUIraffe exposes its resource manager classes
    resourceManager = builderData.getTransformerContext.getResourceManager
    originalResourceLoader = resourceManager.getResourceLoader
    resourceManager.setResourceLoader(new ResourceLoaderImpl(configData.configClassLoader))
  }

  /**
    * @inheritdoc This implementation restores the class loader provider.
    */
  override def onFinally(): Unit = {
    super.onFinally()
    classLoaderProvider.registerClassLoader(MediaIfcConfigData.ConfigClassLoaderName, null)
    resourceManager setResourceLoader originalResourceLoader
  }
}
