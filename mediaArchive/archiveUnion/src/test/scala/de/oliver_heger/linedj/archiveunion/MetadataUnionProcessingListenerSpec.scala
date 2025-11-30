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

import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumDescription, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.Checksums.MediumChecksum
import de.oliver_heger.linedj.shared.archive.metadata.MetadataProcessingEvent
import de.oliver_heger.linedj.shared.archive.union.{MediaContribution, MetadataProcessingResult, UpdateOperationCompleted, UpdateOperationStarts}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths

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
      MetadataProcessingEvent.ScanStarts(processor.ref),
      UpdateOperationStarts(Some(processor.ref))
    )

  it should "forward an UpdateOperationCompleted event" in :
    val helper = new ListenerTestHelper
    val processor = TestProbe()

    helper.sendEventAndExpectForwarding(
      MetadataProcessingEvent.ScanCompleted(processor.ref),
      UpdateOperationCompleted(Some(processor.ref))
    )

  it should "forward a MediumAvailable event" in :
    val helper = new ListenerTestHelper
    val mediaFiles = List(MediaFileUri("file1"), MediaFileUri("file2"), MediaFileUri("file3"))
    val mediumID = MediumID("mediumURI", Some("descPath"), "someComponentID")
    val checksum = MediumChecksum("some-checksum")

    helper.sendEventAndExpectForwarding(
      MetadataProcessingEvent.MediumAvailable(mediumID, checksum, mediaFiles, Paths.get("archiveRoot"), "someArchive"),
      MediaContribution(Map(mediumID -> mediaFiles))
    )

  it should "forward a ProcessingResultAvailable event" in :
    val helper = new ListenerTestHelper
    val result = mock[MetadataProcessingResult]

    helper.sendEventAndExpectForwarding(
      MetadataProcessingEvent.ProcessingResultAvailable(MediumChecksum("check"), result),
      result
    )

  it should "ignore a MediumDescriptionAvailable event" in :
    val helper = new ListenerTestHelper
    val mediumID = MediumID("mediumURI", Some("descPath"), "someComponentID")
    val mediumDescription = MediumDescription("someName", "someDesc", "someOrder")

    helper.checkIgnoredEvent(MetadataProcessingEvent.MediumDescriptionAvailable(mediumID, mediumDescription))

  it should "ignore a ProcessingStarts event" in :
    val helper = new ListenerTestHelper
    val groupManager = TestProbe()

    helper.checkIgnoredEvent(MetadataProcessingEvent.ProcessingStarts(groupManager.ref))

  it should "ignore a ProcessingCompleted event" in :
    val helper = new ListenerTestHelper
    val groupManager = TestProbe()

    helper.checkIgnoredEvent(MetadataProcessingEvent.ProcessingCompleted(groupManager.ref))

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
      sendEvent(event)
      probeMetadataUnionActor.expectMsg(expMessage)
      this

    /**
      * Checks whether the given event is simply ignored and does not cause the 
      * listener to crash.
      *
      * @param event the event
      * @return this test helper
      */
    def checkIgnoredEvent(event: MetadataProcessingEvent): ListenerTestHelper =
      sendEvent(event)
      val processor = TestProbe()

      sendEventAndExpectForwarding(
        MetadataProcessingEvent.ScanStarts(processor.ref),
        UpdateOperationStarts(Some(processor.ref))
      )

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

    /**
      * Sends the given ent to the listener actor to be tested.
      *
      * @param event the event
      * @return this test helper
      */
    private def sendEvent(event: MetadataProcessingEvent): ListenerTestHelper =
      listener ! event
      this
