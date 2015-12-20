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

package de.oliver_heger.linedj.client.app

import akka.actor.ActorSystem
import de.oliver_heger.linedj.client.remoting.{ActorFactory, MessageBus, RemoteMessageBus}
import net.sf.jguiraffe.gui.app.{ApplicationContext, Application}
import net.sf.jguiraffe.gui.platform.javafx.builder.window.StageFactory
import org.apache.commons.configuration.PropertiesConfiguration
import org.mockito.Mockito._
import org.osgi.service.component.ComponentContext
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''ClientApplication''.
  */
class ClientApplicationSpec extends FlatSpec with Matchers with MockitoSugar {

  /**
    * Queries the given application for a bean with a specific name. This bean
    * is checked against a type. If this type is matched, the bean is returned;
    * otherwise, an exception is thrown.
    * @param app the application
    * @param name the name of the bean
    * @param m the manifest
    * @tparam T the expected bean type
    * @return the bean of this type
    */
  private def queryBean[T](app: Application, name: String)(implicit m: Manifest[T]): T = {
    app.getApplicationContext.getBeanContext.getBean(name) match {
      case t: T => t
      case b =>
        throw new AssertionError(s"Unexpected bean for name '$name': $b")
    }
  }

  /**
    * Creates a test application.
    * @return the test application
    */
  private def createApp(): ClientApplication =
    new ClientApplication("testconfig.xml")

  /**
    * Creates a test application and starts it so that it is correctly
    * initialized.
    * @return the test application and the context with mock objects
    */
  private def setUpApp(): (ClientApplication, ClientApplicationContext) = {
    val app = createApp()
    val clientContext = new ClientApplicationContextImpl
    app initClientContext clientContext
    val compContext = mock[ComponentContext]
    app activate compContext
    (app, clientContext)
  }

  "A ClientApplication" should "use the correct configuration" in {
    val (app, _) = setUpApp()

    app.getConfigResourceName should be("testconfig.xml")
  }

  it should "define a bean for the stage factory" in {
    val (app, clientContext) = setUpApp()

    queryBean[StageFactory](app, JavaFxSharedWindowManager.BeanStageFactory) should be
    clientContext.stageFactory
  }

  it should "define a bean for the actor system" in {
    val (app, clientContext) = setUpApp()

    queryBean[ActorSystem](app, ClientApplication.BeanActorSystem) should be(clientContext
      .actorSystem)
  }

  it should "define a bean for the actor factory" in {
    val (app, clientContext) = setUpApp()

    queryBean[ActorFactory](app, ClientApplication.BeanActorFactory) should be(clientContext
      .actorFactory)
  }

  it should "define a bean for the message bus" in {
    val (app, clientContext) = setUpApp()

    queryBean[MessageBus](app, ClientApplication.BeanMessageBus) should be(clientContext.messageBus)
  }

  it should "define a bean for the remote message bus" in {
    val (app, clientContext) = setUpApp()

    queryBean[RemoteMessageBus](app, ClientApplication.BeanRemoteMessageBus) should be
    clientContext.remoteMessageBus
  }

  it should "query the status of the remote message bus" in {
    val app = createApp()
    val appContext = mock[ApplicationContext]
    val config = new PropertiesConfiguration
    when(appContext.getConfiguration).thenReturn(config)
    val clientContext = new ClientApplicationContextImpl
    app initClientContext clientContext

    app.initGUI(appContext)
    verify(appContext).getConfiguration
    verify(clientContext.remoteMessageBus).queryServerState()
  }
}
