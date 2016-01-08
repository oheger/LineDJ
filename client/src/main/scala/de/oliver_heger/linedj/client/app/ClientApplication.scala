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

package de.oliver_heger.linedj.client.app

import net.sf.jguiraffe.gui.app.{ApplicationContext, Application}
import org.osgi.service.component.ComponentContext

object ClientApplication {
  /** The prefix used by all beans managed by this application. */
  val BeanPrefix = "LineDJ_"

  /** The bean for the client-side actor system. */
  val BeanActorSystem = BeanPrefix + "ActorSystem"

  /** The bean for the actor factory. */
  val BeanActorFactory = BeanPrefix + "ActorFactory"

  /** The bean for the message bus. */
  val BeanMessageBus = BeanPrefix + "MessageBus"

  /** The bean for the remote message bus. */
  val BeanRemoteMessageBus = BeanPrefix + "RemoteMessageBus"
}

/**
  * A class representing a visual client application running on the LineDJ
  * (OSGi) platform.
  *
  * This class is more or less a regular JGUIraffe application with an
  * arbitrary main window. However, it implements functionality to obtain some
  * central platform beans from the [[ClientApplicationContext]] and make them
  * available in its own ''BeanContext''.
  *
  * This class is intended to be used as a declarative services component. It
  * needs a static reference to a [[ClientApplicationContext]] service; as soon
  * as this object becomes available, the required properties are fetched from
  * there, and this application can start up. It has to register itself as
  * service of type ''Application''; this establishes the connection to the
  * management application.
  *
  * While this class is fully functional, in order to implement a valid
  * declarative services component, it has to be extended, and the name of the
  * configuration file has to be passed to the constructor.
  *
  * @param configName the name of the configuration file
  */
class ClientApplication(val configName: String) extends Application {
  this: ApplicationStartup =>

  import ClientApplication._

  /** The client application context. */
  private var clientContextField: ClientApplicationContext = _

  /**
    * Initializes the reference to the ''ClientApplicationContext''. This
    * method is called by the SCR.
    * @param context the ''ClientApplicationContext''
    */
  def initClientContext(context: ClientApplicationContext): Unit = {
    clientContextField = context
  }

  /**
    * Returns the ''ClientApplicationContext'' used by this application.
    * @return the ''ClientApplicationContext''
    */
  def clientContext: ClientApplicationContext = clientContextField

  /**
    * Returns the ''ClientApplicationContext'' used by this application. This
    * object is available after the initialization of this application.
    * @return the ''ClientApplicationContext''
    */
  def clientApplicationContext: ClientApplicationContext = clientContext

  /**
    * Activates this component. This method is called by the SCR. It starts
    * the application using the mixed in [[ApplicationStartup]] implementation.
    * @param compContext the component context
    */
  def activate(compContext: ComponentContext): Unit = {
    startApplication(this, configName)
  }

  /**
    * @inheritdoc This implementation adds some beans defined in the client
    *             application context to this application's ''BeanContext'',
    *             so that they are available everywhere in this application.
    */
  override def createApplicationContext(): ApplicationContext = {
    val appCtx = super.createApplicationContext()
    addBeanDuringApplicationStartup(JavaFxSharedWindowManager.BeanStageFactory, clientContext
      .stageFactory)
    addBeanDuringApplicationStartup(BeanActorSystem, clientContext.actorSystem)
    addBeanDuringApplicationStartup(BeanActorFactory, clientContext.actorFactory)
    addBeanDuringApplicationStartup(BeanMessageBus, clientContext.messageBus)
    addBeanDuringApplicationStartup(BeanRemoteMessageBus, clientContext.remoteMessageBus)
    appCtx
  }

  /**
    * @inheritdoc This implementation queries the status of the remote message
    *             bus after the UI has been initialized. This causes a status
    *             message to be sent which triggers some further initialization
    *             of the UI.
    */
  override def initGUI(appCtx: ApplicationContext): Unit = {
    super.initGUI(appCtx)
    clientApplicationContext.remoteMessageBus.queryServerState()
  }
}
