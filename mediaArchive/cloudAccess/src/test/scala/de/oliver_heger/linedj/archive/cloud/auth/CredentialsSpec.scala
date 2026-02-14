/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.archive.cloud.auth

import com.github.cloudfiles.core.http.Secret
import de.oliver_heger.linedj.shared.actors.{ActorFactory, ManagingActorFactory}
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.Timeout
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt

/**
  * Test class for [[Credentials]].
  */
class CredentialsSpec(testSystem: classic.ActorSystem) extends TestKit(testSystem), AsyncFlatSpecLike,
  BeforeAndAfterAll, Matchers:
  def this() = this(classic.ActorSystem("CredentialsSpec"))

  /** The test kit for typed actors. */
  private val typedTestKit = ActorTestKit()

  override protected def afterAll(): Unit =
    typedTestKit.shutdownTestKit()
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import Credentials.given

  /**
    * Returns the [[ActorFactory]] based on the actor system of this test
    * class.
    *
    * @return the actor factory for tests
    */
  private def actorFactory: ActorFactory = implicitly

  "A credentials manager actor" should "stop itself on receiving a Stop command" in :
    val (actor, _) = Credentials.setUpCredentialsManager(actorFactory)

    actor ! Credentials.Stop

    val probe = typedTestKit.createDeadLetterProbe()
    probe.expectTerminated(actor)
    succeed

  it should "pass existing credentials to requesting clients" in :
    val credentialData = Credentials.CredentialData("someKey", Secret("very-secret"))
    val probe = typedTestKit.createTestProbe[Credentials.CredentialData]()
    val (actor, _) = Credentials.setUpCredentialsManager(actorFactory)

    actor ! credentialData
    actor ! Credentials.QueryCredential(credentialData.key, probe.ref)

    probe.expectMessage(credentialData)
    succeed

  it should "notify clients when their credentials become available" in :
    val credentialData1 = Credentials.CredentialData("cred1", Secret("sec1"))
    val credentialData2 = Credentials.CredentialData("cred2", Secret("sec2"))
    val client1 = typedTestKit.createTestProbe[Credentials.CredentialData]()
    val client2 = typedTestKit.createTestProbe[Credentials.CredentialData]()
    val client3 = typedTestKit.createTestProbe[Credentials.CredentialData]()
    val (actor, _) = Credentials.setUpCredentialsManager(actorFactory, actorName = "queryThenSet")

    actor ! Credentials.QueryCredential(credentialData1.key, client1.ref)
    actor ! Credentials.QueryCredential(credentialData2.key, client2.ref)
    actor ! Credentials.QueryCredential(credentialData1.key, client3.ref)
    actor ! credentialData1
    actor ! credentialData2

    client1.expectMessage(credentialData1)
    client2.expectMessage(credentialData2)
    client3.expectMessage(credentialData1)
    succeed

  it should "notify clients only once about incoming credentials" in :
    val credentialData = Credentials.CredentialData("key", Secret("onlyOnce"))
    val client = typedTestKit.createTestProbe[Credentials.CredentialData]()
    val (actor, _) = Credentials.setUpCredentialsManager(actorFactory, actorName = "multiSet")

    actor ! Credentials.QueryCredential(credentialData.key, client.ref)
    actor ! credentialData
    actor ! credentialData.copy(value = Secret("anotherValue"))

    client.expectMessage(credentialData)
    client.expectNoMessage(100.millis)
    succeed

  "The resolver function" should "return the correct credentials" in :
    val credentialData1 = Credentials.CredentialData("cred1", Secret("sec1"))
    val credentialData2 = Credentials.CredentialData("cred2", Secret("sec2"))
    val (actor, resolver) = Credentials.setUpCredentialsManager(actorFactory, actorName = "resolverFunc")

    val futSecrets = for
      sec1 <- resolver(credentialData1.key)
      sec2 <- resolver(credentialData2.key)
    yield (sec1, sec2)

    actor ! credentialData2
    actor ! credentialData1
    futSecrets map : (sec1, sec2) =>
      sec1 should be(credentialData1.value)
      sec2 should be(credentialData2.value)

  "setUpCredentialsManager" should "set the stop command for the actor" in :
    val managingFactory = ManagingActorFactory.newDefaultManagingActorFactory(using actorFactory)

    val (actor, _) = Credentials.setUpCredentialsManager(managingFactory, actorName = "withManagingFactory")
    managingFactory.stopActors()

    val probe = typedTestKit.createDeadLetterProbe()
    probe.expectTerminated(actor)
    succeed

  it should "take the timeout into account" in :
    val shortTimeout = Timeout(10.millis)
    val processingTime = 500.millis
    val (actor, resolver) = Credentials.setUpCredentialsManager(
      actorFactory, actorName = "runIntoTimeout"
    )(using shortTimeout)

    typedTestKit.scheduler.scheduleOnce(processingTime, () => actor ! Credentials.CredentialData("k", Secret("v")))
    recoverToSucceededIf[TimeoutException]:
      resolver("k")
