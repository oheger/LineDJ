/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.utils

import de.oliver_heger.linedj.utils.ActorManagement.ActorStopper
import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.atomic.AtomicBoolean

object ActorManagementSpec:
  /** Prefix for an actor name. */
  private val ActorName = "testActor_"

  /**
    * Generates an actor name based on the given index.
    *
    * @param idx the index
    * @return the actor name
    */
  private def genActorName(idx: Int): String = ActorName + idx

/**
  * Test class for ''ActorManagement''.
  */
class ActorManagementSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar:

  import ActorManagementSpec._

  def this() = this(ActorSystem("ActorManagementSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  "An ActorManagement" should "make registered actors accessible" in:
    val helper = new ActorManagementTestHelper

    val (name1, actor1) = helper.registerActor()
    val (name2, actor2) = helper.registerActor()
    helper.management.getActor(name1) should be(actor1)
    helper.management.getActor(name2) should be(actor2)

  it should "throw an exception when querying an unknown actor" in:
    val helper = new ActorManagementTestHelper

    intercept[NoSuchElementException]:
      helper.management getActor genActorName(1)

  it should "stop registered actors" in:
    val helper = new ActorManagementTestHelper
    val (_, actor1) = helper.registerActor()
    val (_, actor2) = helper.registerActor()

    helper.stopActors()
      .checkActorsStopped(actor1, actor2)

  it should "allow stopping managed actors directly" in:
    val helper = new ActorManagementTestHelper
    val (name, actor) = helper.registerActor()

    helper.management.stopActors()
    helper.checkActorsStopped(actor)
    intercept[NoSuchElementException]:
      helper.management getActor name

  it should "return an empty set if no actors have been registered yet" in:
    val helper = new ActorManagementTestHelper

    helper.management.managedActorNames shouldBe empty

  it should "return the names of registered actors" in:
    val helper = new ActorManagementTestHelper
    val expNames = (1 to 10).map(_ => helper.registerActor()._1)

    helper.management.managedActorNames should contain theSameElementsAs expNames

  it should "return None when removing an unknown actor" in:
    val helper = new ActorManagementTestHelper

    helper.management.unregisterActor("someActor") shouldBe empty

  it should "support removing a registration for an actor" in:
    val helper = new ActorManagementTestHelper
    val (name1, _) = helper.registerActor()
    val (name2, actor2) = helper.registerActor()

    helper.management.unregisterActor(name2) should be(Some(actor2))
    helper.management.managedActorNames should contain only name1

  it should "return false for an attempt to stop an unknown actor" in:
    val helper = new ActorManagementTestHelper

    helper.management.unregisterAndStopActor("someActor") shouldBe false

  it should "support removing and stopping an actor" in:
    val helper = new ActorManagementTestHelper
    val (name1, _) = helper.registerActor()
    val (name2, actor2) = helper.registerActor()

    helper.management.unregisterAndStopActor(name2) shouldBe true
    helper.checkActorsStopped(actor2)
    helper.management.managedActorNames should contain only name1

  it should "support registering only an object to stop actors" in:
    val stopFlag = new AtomicBoolean
    val stopper = new ActorManagement.ActorStopper:
      override def stop(): Unit = stopFlag.set(true)
    val helper = new ActorManagementTestHelper

    helper.management.registerActor("actorWithStopper", stopper)
    helper.stopActors()
    stopFlag.get() shouldBe true

  it should "throw an exception when querying a registered actor for which no reference is available" in:
    val ActorName = "actorWithoutRef"
    val stopper = mock[ActorStopper]
    val helper = new ActorManagementTestHelper

    helper.management.registerActor(ActorName, stopper)
    intercept[NoSuchElementException]:
      helper.management getActor ActorName

  /**
    * A helper class managing a test instance and its dependencies.
    */
  private class ActorManagementTestHelper:
    /** The instance to be tested. */
    val management: ActorManagement = createTestInstance()

    /** A counter for generating actor names. */
    private var actorCount = 0

    /**
      * Calls ''stopActors()'' on the test instance.
      *
      * @return this test helper
      */
    def stopActors(): ActorManagementTestHelper =
      management.stopActors()
      this

    /**
      * Creates a mock actor and registers it at the test component.
      *
      * @return a pair of the actor name and the actor reference
      */
    def registerActor(): (String, ActorRef) =
      actorCount += 1
      val name = genActorName(actorCount)
      val actor = TestProbe().ref
      management.registerActor(name, actor)
      (name, actor)

    /**
      * Checks whether all of the specified actors have been stopped.
      *
      * @param refs the expected actor references
      * @return this test helper
      */
    def checkActorsStopped(refs: ActorRef*): ActorManagementTestHelper =
      refs foreach { ref =>
        val watcher = TestProbe()
        watcher watch ref
        watcher.expectTerminated(ref)
      }
      this

    /**
      * Creates a test instance.
      *
      * @return the test instance
      */
    private def createTestInstance(): ActorManagement = new ActorManagement {}
