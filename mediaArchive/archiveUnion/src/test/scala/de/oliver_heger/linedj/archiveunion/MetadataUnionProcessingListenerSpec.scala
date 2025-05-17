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

package de.oliver_heger.linedj.archiveunion

import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.{MediumChecksum, MetadataProcessingEvent}
import de.oliver_heger.linedj.shared.archive.union.{MediaContribution, MetadataProcessingResult, UpdateOperationCompleted, UpdateOperationStarts}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for [[MetadataUnionProcessingListener]].
  */
class MetadataUnionProcessingListenerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar:
  def this() = this(ActorSystem("MetadataUnionProcessingListenerSpec"))

  /** The test kit for testing typed actors. */
  private val typedTestKit = ActorTestKit()

  override protected def afterAll(): Unit =
    typedTestKit.shutdownTestKit()
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  "A MetadataUnionProcessingListener" should "forward an UpdateOperationStarts event" in :
    val helper = new ListenerTestHelper
    val processor = TestProbe()

    helper.sendEventAndExpectForwarding(
      MetadataProcessingEvent.UpdateOperationStarts(processor.ref),
      UpdateOperationStarts(Some(processor.ref))
    )

  it should "forward an UpdateOperationCompleted event" in :
    val helper = new ListenerTestHelper
    val processor = TestProbe()

    helper.sendEventAndExpectForwarding(
      MetadataProcessingEvent.UpdateOperationCompleted(processor.ref),
      UpdateOperationCompleted(Some(processor.ref))
    )

  it should "forward a MediumAvailable event" in :
    val helper = new ListenerTestHelper
    val mediaFiles = List(MediaFileUri("file1"), MediaFileUri("file2"), MediaFileUri("file3"))
    val mediumID = MediumID("mediumURI", Some("descPath"), "someComponentID")
    val checksum = MediumChecksum("some-checksum")

    helper.sendEventAndExpectForwarding(
      MetadataProcessingEvent.MediumAvailable(mediumID, checksum, mediaFiles),
      MediaContribution(Map(mediumID -> mediaFiles))
    )

  it should "forward a ProcessingResultAvailable event" in :
    val helper = new ListenerTestHelper
    val result = mock[MetadataProcessingResult]

    helper.sendEventAndExpectForwarding(
      MetadataProcessingEvent.ProcessingResultAvailable(MediumChecksum("check"), result),
      result
    )

  it should "stop itself when the metadata union actor terminates" in :
    val helper = new ListenerTestHelper

    helper.stopUnionActor()
      .expectListenerTerminated()

  /**
    * A test helper class managing an actor to be tested and its dependencies.
    */
  private class ListenerTestHelper:
    /** Test probe for the metadata union actor. */
    private val probeMetadataUnionActor = TestProbe()

    /** The actor to be tested. */
    private val listener = typedTestKit.spawn(MetadataUnionProcessingListener.behavior(probeMetadataUnionActor.ref))

    /**
      * Sends the given event to the listener actor to be tested and expects
      * that a specific message is received by the metadata union actor.
      *
      * @param event      the event
      * @param expMessage the expected message to the union actor
      * @return this test helper
      */
    def sendEventAndExpectForwarding(event: MetadataProcessingEvent, expMessage: Any): ListenerTestHelper =
      listener ! event
      probeMetadataUnionActor.expectMsg(expMessage)
      this

    /**
      * Stops the metadata union actor. This can be used to test whether the
      * listener correctly watches this actor.
      *
      * @return this test helper
      */
    def stopUnionActor(): ListenerTestHelper =
      system.stop(probeMetadataUnionActor.ref)
      this

    /**
      * Expects that the listener actor has stopped itself.
      *
      * @return this test helper
      */
    def expectListenerTerminated(): ListenerTestHelper =
      val watcherProbe = typedTestKit.createDeadLetterProbe()
      watcherProbe.expectTerminated(listener)
      this
