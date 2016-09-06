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

  /** The bean for the media facade. */
  val BeanMediaFacade = BeanPrefix + "MediaFacade"

  /** The bean for the whole client application context. */
  val BeanClientApplicationContext = BeanPrefix + "ClientApplicationContext"

  /**
    * The bean for the message bus registration. If a bean with this name is
    * available in the bean context for the main window, it is requested and
    * thus initialized automatically.
    */
  val BeanMessageBusRegistration = BeanPrefix + "messageBusRegistration"

  /**
    * The name of a blocking dispatcher in the actor system configuration.
    * Client applications can use this dispatcher for actors that do blocking
    * operations of any kind.
    */
  val BlockingDispatcherName = "blocking-dispatcher"

  /**
    * A life-cycle message indicating the completed initialization of a
    * [[ClientApplication]]. A message of this type is published on the UI
    * message bus after an application has been fully initialized.
    *
    * @param application the application that has been initialized
    */
  case class ClientApplicationInitialized(application: ClientApplication)
}

/**
  * A class representing a visual client application running on the LineDJ
  * (OSGi) platform.
  *
  * This class is more or less a regular JGUIraffe application with an
  * arbitrary main window. It serves as base class for all visual LineDJ
  * applications. In this role, it provides additional functionality that can
  * be used by derived classes:
  *
  * '''Management of central beans'''
  *
  * At startup, an instance obtains some central platform beans from the
  * [[ClientApplicationContext]] and makes them available in its own
  * ''BeanContext''. These are the following beans:
  *
  * $ - ''LineDJ_ActorSystem'': The actor system used by the platform.
  * $ - ''LineDJ_ActorFactory'': The helper object for creating new actors.
  * $ - ''LineDJ_MessageBus'': The UI message bus.
  * $ - ''LineDJ_MediaFacade'': The facade to the media archive.
  * $ - ''LineDJ_ClientApplicationContext'': The [[ClientApplicationContext]]
  * itself.
  *
  * There is also some support for registering listeners on the system-wide
  * message bus: If the Jelly script for the main window defines a bean named
  * ''LineDJ_messageBusRegistration'', this bean is fetched during startup and
  * thus initialized. This typically causes the registration of the listeners
  * used by this application.
  *
  * '''Life-cycle management'''
  *
  * The class implements the typical life-cycle hooks of a JGUIraffe
  * application to make sure that state related to the LineDJ platform gets
  * correctly initialized. This includes publishing some life-cycle
  * notifications on the UI message bus:
  *
  * $ - A [[de.oliver_heger.linedj.client.app.ClientApplication.ClientApplicationInitialized]] message for this application is
  * published after initialization is complete (and the Jelly script for the
  * main window has been executed).
  * $ - The state of the media archive is queried, so that a corresponding
  * state message will be published on the message bus.
  *
  * '''Further notes'''
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
  * @param appName the name of this application
  */
class ClientApplication(val appName: String) extends Application {
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
    startApplication(this, appName)
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
    addBeanDuringApplicationStartup(BeanMediaFacade, clientContext.mediaFacade)
    addBeanDuringApplicationStartup(BeanClientApplicationContext, clientContext)
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
    if (getMainWindowBeanContext != null &&
      getMainWindowBeanContext.containsBean(BeanMessageBusRegistration)) {
      // trigger initialization of this bean
      getMainWindowBeanContext getBean BeanMessageBusRegistration
    }

    clientApplicationContext.messageBus publish ClientApplicationInitialized(this)
    clientApplicationContext.mediaFacade.requestMediaState()
  }
}
