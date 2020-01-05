/*
 * Copyright 2015-2019 The Developers Team.
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

package de.oliver_heger.linedj.archiveadmin

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import de.oliver_heger.linedj.archiveadmin.ArchiveAdminAppSpec.ActorInvocation
import de.oliver_heger.linedj.platform.app._
import de.oliver_heger.linedj.platform.app.support.ActorClientSupport.ActorRequest
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaFacadeActors
import de.oliver_heger.linedj.platform.mediaifc.ext.ConsumerRegistrationProcessor
import org.apache.commons.configuration.PropertiesConfiguration
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.collection.immutable.Queue
import scala.concurrent.duration._

object ArchiveAdminAppSpec {

  /**
    * A data class that records an actor invocation via the application.
    *
    * @param actor   the actor that was invoked
    * @param message the message passed to the actor
    * @param timeout the timeout
    */
  private case class ActorInvocation(actor: ActorRef, message: Any, timeout: Timeout)

}

/**
  * Test class for ''ArchiveAdminApp''.
  */
class ArchiveAdminAppSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
  with BeforeAndAfterAll with Matchers with ApplicationTestSupport with MockitoSugar {
  def this() = this(ActorSystem("ArchiveAdminAppSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  "An ArchiveAdminApp" should "construct an instance correctly" in {
    val app = new ArchiveAdminApp

    app shouldBe a[ApplicationAsyncStartup]
    app.appName should be("archiveAdmin")
  }

  it should "define a correct consumer registration bean" in {
    val application = activateApp(new ArchiveAdminAppTestImpl)

    val consumerReg = queryBean[ConsumerRegistrationProcessor](application
      .getMainWindowBeanContext, ClientApplication.BeanConsumerRegistration)
    val controller = queryBean[ArchiveAdminController](application.getMainWindowBeanContext,
      "adminController")
    consumerReg.providers should contain only controller
  }

  it should "return the correct media facade actors" in {
    val facadeActors = mock[MediaFacadeActors]
    val application = new ArchiveAdminAppTestImpl

    application initFacadeActors facadeActors
    application.mediaFacadeActors should be(facadeActors)
  }

  it should "support invoking an actor with a configured request" in {
    val TimeoutSec = 42
    val config = new PropertiesConfiguration
    config.addProperty(ArchiveAdminApp.PropActorTimeout, TimeoutSec)
    val actor = TestProbe().ref
    val message = "Test message"
    val application = activateApp(new ArchiveAdminAppTestImpl, config)

    application.invokeActor(actor, message) should be(application.actorRequest)
    val (invocation, _) = application.actorRequestQueue.dequeue
    invocation should be(ActorInvocation(actor, message, Timeout(TimeoutSec.seconds)))
  }

  it should "support invoking an actor with a default timeout" in {
    val actor = TestProbe().ref
    val message = "Test message"
    val application = activateApp(new ArchiveAdminAppTestImpl)

    application.invokeActor(actor, message) should be(application.actorRequest)
    val (invocation, _) = application.actorRequestQueue.dequeue
    invocation should be(ActorInvocation(actor, message, Timeout(10.seconds)))
  }

  /**
    * A test application implementation that starts up synchronously and allows
    * inspecting actor requests.
    */
  private class ArchiveAdminAppTestImpl extends ArchiveAdminApp with ApplicationSyncStartup with AppWithTestPlatform {
    /** Mock for an actor request object. */
    val actorRequest: ActorRequest = mock[ActorRequest]

    /** A queue to record actor invocations. */
    var actorRequestQueue: Queue[ActorInvocation] = Queue.empty

    /**
      * @inheritdoc This implementation records the invocation.
      */
    override def actorRequest(actor: ActorRef, msg: Any)(implicit timeout: Timeout): ActorRequest = {
      actorRequestQueue = actorRequestQueue.enqueue(ActorInvocation(actor, msg, timeout))
      actorRequest
    }
  }

}
