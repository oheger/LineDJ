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

package de.oliver_heger.linedj.browser.app

import java.util.concurrent.{TimeUnit, CountDownLatch}

import de.oliver_heger.linedj.ActorSystemTestHelper
import org.mockito.ArgumentCaptor
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.osgi.framework.{Bundle, BundleContext}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, Matchers, FlatSpec}

/**
  * Test class for ''BrowserActivator''.
  */
class BrowserActivatorSpec extends FlatSpec with Matchers with BeforeAndAfterAll with MockitoSugar with ActorSystemTestHelper {
  override val actorSystemName = "BrowserActivatorSpec"

  override protected def afterAll(): Unit = {
    shutdownActorSystem()
  }

  /**
    * Invokes the activator under test in order to obtain a mock application
    * which has been created by the activator. This mock can then be
    * inspected to verify whether it has been properly initialized.
    * @param bc the bundle context to be used
    * @return the mock application created by the activator
    */
  private def createAppInActivator(bc: BundleContext): BrowserApp = {
    val factory = mock[ApplicationFactory]
    val app = mock[BrowserApp]
    val latch = new CountDownLatch(1)
    when(factory.createApplication(Some(testActorSystem))).thenAnswer(new Answer[BrowserApp] {
      override def answer(invocationOnMock: InvocationOnMock): BrowserApp = {
        app
      }
    })
    doAnswer(new Answer[Object] {
      override def answer(invocationOnMock: InvocationOnMock): Object = {
        latch.countDown() // called in a different thread
        null
      }
    }).when(app).run()
    val activator = new BrowserActivator(factory)
    activator.configure(bc, testActorSystem)
    latch.await(5, TimeUnit.SECONDS) shouldBe true
    app
  }

  "A BrowserActivator" should "have a functional default application factory" in {
    val activator = new BrowserActivator

    val factory = activator.applicationFactory
    val app = factory createApplication Some(testActorSystem)
    app.remoteMessageBusFactory should not be null
    app.optActorSystem.get should be(testActorSystem)
  }

  it should "create and start an application" in {
    val app = createAppInActivator(mock[BundleContext])

    val argsCaptor = ArgumentCaptor.forClass(classOf[Array[String]])
    verify(app).processCommandLine(argsCaptor.capture())
    argsCaptor.getValue should have length 0
    verify(app).run()
  }

  it should "set a correct exit handler for the application" in {
    val bc = mock[BundleContext]
    val bundle = mock[Bundle]
    val app = createAppInActivator(bc)
    when(bc.getBundle(0)).thenReturn(bundle)

    val exitCaptor = ArgumentCaptor.forClass(classOf[Runnable])
    verify(app).setExitHandler(exitCaptor.capture())
    exitCaptor.getValue.run()
    verify(bundle).stop()
  }
}
