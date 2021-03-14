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

import java.util

import net.sf.jguiraffe.di.ClassLoaderProvider
import net.sf.jguiraffe.gui.app.{ApplicationBuilderData, OpenWindowCommand}
import net.sf.jguiraffe.resources.ResourceManager
import net.sf.jguiraffe.resources.impl.ResourceManagerImpl
import net.sf.jguiraffe.resources.impl.bundle.BundleResourceLoader
import net.sf.jguiraffe.transform.{TransformerContext, TransformerContextPropertiesWrapper}

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

  /**
    * @inheritdoc This implementation registers the class loader for the
    *             configuration dialog.
    */
  override protected[config] def prepareBuilderData(builderData: ApplicationBuilderData): Unit = {
    super.prepareBuilderData(builderData)
    classLoaderProvider = registerConfigClassLoader(builderData)
    val resMan = createResourceManager(classLoaderProvider)
    val transCtx = createTransformerContext(builderData, resMan)
    builderData setTransformerContext transCtx
  }

  /**
    * @inheritdoc This implementation restores the class loader provider.
    */
  override def onFinally(): Unit = {
    super.onFinally()
    classLoaderProvider.registerClassLoader(MediaIfcConfigData.ConfigClassLoaderName, null)
  }

  /**
    * Adds a class loader to access classes from the configuration data to the
    * ''ClassLoaderProvider'' of the builder.
    *
    * @param builderData the builder data object
    * @return the modified ''ClassLoaderProvider''
    */
  private def registerConfigClassLoader(builderData: ApplicationBuilderData):
  ClassLoaderProvider = {
    val clp = builderData.getParentContext.getClassLoaderProvider
    clp.registerClassLoader(MediaIfcConfigData.ConfigClassLoaderName,
      configData.configClassLoader)
    clp
  }

  /**
    * Creates the ''ResourceManager'' to be used when executing the Jelly
    * script for the media interface configuration.
    *
    * @param clp the ''ClassLoaderProvider''
    * @return the new resource manager
    */
  private def createResourceManager(clp: ClassLoaderProvider): ResourceManager =
    new ResourceManagerImpl(new BundleResourceLoader(clp,
      MediaIfcConfigData.ConfigClassLoaderName))

  /**
    * Creates a special ''TransformerContext'' for the execution of the Jelly
    * script for the media interface configuration. The context uses the
    * resource manager specified.
    *
    * @param builderData the builder data
    * @param resMan      the resource manager to be used
    * @return the ''TransformerContext''
    */
  private def createTransformerContext(builderData: ApplicationBuilderData,
                                       resMan: ResourceManager): TransformerContext =
    new TransformerContextPropertiesWrapper(builderData.getTransformerContext,
      new util.HashMap) {
      override def getResourceManager: ResourceManager = resMan
    }
}
