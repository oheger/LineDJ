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
import org.scalatest.flatspec.FixtureAsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, FutureOutcome}

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt

/**
  * Test class for [[Credentials]].
  */
class CredentialsSpec(testSystem: classic.ActorSystem) extends TestKit(testSystem), FixtureAsyncFlatSpecLike,
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
    * A data class to represent the fixture used by the test cases.
    *
    * @param setter       the setter for credentials
    * @param resolver     the credentials resolver function
    * @param actorFactory the actor factory
    */
  case class FixtureParam(setter: Credentials.CredentialSetter,
                          resolver: Credentials.ResolverFunc,
                          actorFactory: ManagingActorFactory)

  override def withFixture(test: OneArgAsyncTest): FutureOutcome =
    val actorFactory = ManagingActorFactory.newDefaultManagingActorFactory
    val (setter, resolver) = Credentials.setUpCredentialsManager(actorFactory)
    val fixture = FixtureParam(setter, resolver, actorFactory)

    complete:
      withFixture(test.toNoArgAsyncTest(fixture))
    .lastly:
      actorFactory.stopActors()

  "Credentials management" should "pass existing credentials to requesting clients" in : fixture =>
    val key = "someKey"
    val value = Secret("very-secret")

    for
      setResult <- fixture.setter.setCredential(key, value)
      secret <- fixture.resolver(key)
    yield
      setResult shouldBe false
      secret should be(value)

  it should "notify clients when their credentials become available" in : fixture =>
    val key1 = "cred1"
    val key2 = "cred2"
    val value1 = Secret("sec1")
    val value2 = Secret("sec2")

    val futSec1 = fixture.resolver(key1)
    val futSec2 = fixture.resolver(key2)
    val futSec1_2 = fixture.resolver(key1)

    for
      setResult1 <- fixture.setter.setCredential(key1, value1)
      setResult2 <- fixture.setter.setCredential(key2, value2)
      secret1 <- futSec1
      secret2 <- futSec2
      secret1_2 <- futSec1_2
    yield
      setResult1 shouldBe true
      setResult2 shouldBe true
      secret1 should be(value1)
      secret1_2 should be(value1)
      secret2 should be(value2)

  it should "notify clients only once about incoming credentials" in : fixture =>
    val key = "key"
    val value = Secret("onlyOnce")
    val deadLetterProbe = typedTestKit.createDeadLetterProbe()

    val futSecret = fixture.resolver(key)
    fixture.setter.setCredential(key, value)

    fixture.setter.setCredential(key, Secret("someNewValue"))
    deadLetterProbe.expectNoMessage(100.millis)
    succeed

  it should "take the timeout into account" in : fixture =>
    // This is necessary for unclear reasons to prevent an actor name not unique exception.
    fixture.actorFactory.stopActors()

    val shortTimeout = Timeout(10.millis)
    val processingTime = 500.millis
    val (setter, resolver) = Credentials.setUpCredentialsManager(
      fixture.actorFactory, actorName = "runIntoTimeout"
    )(using shortTimeout)

    typedTestKit.scheduler.scheduleOnce(processingTime, () => fixture.setter.setCredential("k", Secret("v")))
    recoverToSucceededIf[TimeoutException]:
      resolver("k")

  it should "allow clearing credentials" in : fixture =>
    val key = "clearedCredential"
    val value = Secret("theCorrectValue")
    fixture.setter.setCredential(key, Secret("Wrong value"))

    fixture.setter.clearCredential(key)
    val futSecret = fixture.resolver(key)
    fixture.setter.setCredential(key, value)

    futSecret map : secret =>
      secret should be(value)
