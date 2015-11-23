/*
 * Copyright 2015 The Developers Team.
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
package de.oliver_heger.linedj.browser

import akka.actor.ActorSystem
import de.oliver_heger.linedj.remoting.{ActorFactory, RemoteMessageBus}
import net.sf.jguiraffe.gui.app.{Application, ApplicationContext}

import scala.concurrent.duration._

object BrowserApp {
  /** The bean name for the browser configuration. */
  val BeanBrowserConfig = "browserApp_Configuration"

  /** The bean name for the actor system started by the application. */
  val BeanActorSystem = "browserApp_ActorSystem"

  /** The bean name for the actor factory created by the application. */
  val BeanActorFactory = "browserApp_ActorFactory"

  /** The bean name for the message bus. */
  val BeanMessageBus = "browserApp_MessageBus"

  /** The bean name for the remote message bus. */
  val BeanRemoteMessageBus = "browserApp_RemoteMessageBus"

  def main(args: Array[String]): Unit = {
    Application.startup(new BrowserApp, args)
  }
}

/**
 * Main class of LineDJ Browser application.
 *
 * @param remoteMessageBusFactory a factory for a remote message bus
 * @param optActorSystem an optional actor system which is to be used by the
 *                       application if specified
 */
class BrowserApp(private[browser] val remoteMessageBusFactory: RemoteMessageBusFactory,
                 private[browser] val optActorSystem: Option[ActorSystem]) extends
Application {

  import BrowserApp._

  /**
   * Creates a new instance of ''BrowserApp'' with default settings.
   */
  def this() = this(new RemoteMessageBusFactory, None)

  /**
   * @inheritdoc This implementation creates some additional beans, especially
   *             the actor system.
   */
  override protected def createApplicationContext(): ApplicationContext = {
    initActorSystem(super.createApplicationContext())
  }

  /**
   * @inheritdoc This implementation activates the remote message bus after all
   *             listeners have been registered during the execution of the
   *             main UI script.
   */
  override protected def initGUI(appCtx: ApplicationContext): Unit = {
    super.initGUI(appCtx)

    val remoteMessageBus = appCtx.getBeanContext.getBean(BeanRemoteMessageBus)
      .asInstanceOf[RemoteMessageBus]
    remoteMessageBus activate true
  }

  /**
   * @inheritdoc This implementation shuts down the application's actor system.
   */
  override protected def onShutdown(): Unit = {
    val actorSystem = getApplicationContext.getBeanContext.getBean(BeanActorSystem)
      .asInstanceOf[ActorSystem]
    actorSystem.shutdown()
    actorSystem.awaitTermination(10.seconds)

    super.onShutdown()
  }

  /**
   * Initializes the actor system and related beans. These are stored in the
   * global bean context.
   * @param context the application context
   * @return the modified application context
   */
  private def initActorSystem(context: ApplicationContext): ApplicationContext = {
    val actorSystem = optActorSystem getOrElse ActorSystem("BrowserApplication")
    addBean(BeanActorSystem, actorSystem)
    addBean(BeanActorFactory, new ActorFactory(actorSystem))
    addBean(BeanRemoteMessageBus, remoteMessageBusFactory recreateRemoteMessageBus context)
    context
  }

  /**
   * Helper method for adding a constant bean to the application's root store.
   * This can be used to add beans created manually during the initialization
   * phase.
   * @param name the name of the bean
   * @param bean the bean
   * @tparam T the type of the bean
   * @return the bean instance
   */
  private def addBean[T](name: String, bean: T): T = {
    addBeanDuringApplicationStartup(name, bean)
    bean
  }
}
