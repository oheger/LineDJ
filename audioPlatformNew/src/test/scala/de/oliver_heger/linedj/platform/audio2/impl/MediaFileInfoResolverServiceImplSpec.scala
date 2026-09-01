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
package de.oliver_heger.linedj.platform.audio2.impl

import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.app.ClientApplicationContext
import de.oliver_heger.linedj.platform.archiveclient.ArchiveService
import de.oliver_heger.linedj.shared.actors.{ActorFactory, TrackingActorFactory}
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.testkit.TestKit
import org.mockito.ArgumentMatchers.{any, eq as argEq}
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future
import scala.util.{Success, Try}

/**
  * Companion object for [[MediaFileInfoResolverServiceImplSpec]] providing
  * factory functions for test data.
  */
object MediaFileInfoResolverServiceImplSpec:
  /** The base name for synthetic file IDs. */
  private val FileIDPrefix = "testFileID"

  /** The base URI for synthetic media files. */
  private val FileURIPrefix = "/test files/file"

  /**
    * Generates a synthetic ID for a media file based on the given index.
    *
    * @param index the index of the file
    * @return the generated file ID
    */
  private def createFileID(index: Int): String = s"$FileIDPrefix$index"

  /**
    * Generates a synthetic ''MediaFileInfo'' object for the media file with
    * the given index.
    *
    * @param index the index of the file
    * @return the generated info object
    */
  private def createFileInfo(index: Int): ArchiveModel.MediaFileInfo =
    val metaData = MediaMetadata(
      title = Some(s"Title $index"),
      artist = Some(s"Artist $index"),
      album = Some(s"Album $index"),
      inceptionYear = Some(2000 + index),
      trackNumber = Some(index),
      duration = Some(index * 60),
      formatDescription = Some("TestFormat"),
      size = index * 100,
      checksum = s"checksum-$index"
    )
    ArchiveModel.MediaFileInfo(
      metadata = metaData,
      fileUri = MediaFileUri(s"$FileURIPrefix$index.mp3"),
      mediumID = Checksums.MediumChecksum(s"medium-$index")
    )

  /**
    * Generates a map with synthetic info objects for the given number of media
    * files. Each entry maps the generated file ID to the corresponding info
    * object.
    *
    * @param count the number of info objects to generate
    * @return a map with file IDs and info objects
    */
  private def createFileInfos(count: Int): Map[String, ArchiveModel.MediaFileInfo] =
    (0 until count).map: index =>
      createFileID(index) -> createFileInfo(index)
    .toMap
end MediaFileInfoResolverServiceImplSpec

/**
  * Test class for [[MediaFileInfoResolverServiceImpl]].
  */
class MediaFileInfoResolverServiceImplSpec(testSystem: ActorSystem) extends TestKit(testSystem), AnyFlatSpecLike,
  BeforeAndAfterAll, Matchers, MockitoSugar:
  def this() = this(ActorSystem("MediaFileInfoResolverServiceImplSpec"))

  /** The test kit for typed actors. */
  private val typedTestKit = ActorTestKit()

  override protected def afterAll(): Unit =
    typedTestKit.shutdownTestKit()
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import MediaFileInfoResolverServiceImplSpec.*

  "A MediaFileInfoResolverServiceImpl" should "resolve the passed in file IDs" in :
    val fileInfo = createFileInfos(8)
    val helper = new ResolverServiceTestHelper

    helper.expectFileInfoRequest(fileInfo)
      .resolveFileIDs(fileInfo.keySet)
      .expectFutureMessage()
      .resolvedResult should be(Success(fileInfo))

    helper.deactivate()

  it should "return cached info objects on a repeated request" in :
    val fileInfo = createFileInfos(8)
    val helper = new ResolverServiceTestHelper

    helper.expectFileInfoRequest(fileInfo)
      .resolveFileIDs(fileInfo.keySet)
      .expectFutureMessage()
      .resolveFileIDs(fileInfo.keySet)
      .expectFutureMessage()
      .resolvedResult should be(Success(fileInfo))

    helper.verifyIDsRequested(fileInfo.keySet)
      .deactivate()

  it should "handle a failure to resolve a file ID" in :
    val helper = new ResolverServiceTestHelper

    helper.expectFileInfoRequest(createFileInfos(4))
      .resolveFileIDs(List(createFileID(1), createFileID(2), "some-other-ID", createFileID(3)))
      .expectFutureMessage()
      .resolvedResult.isFailure shouldBe true

  it should "handle a deactivate call if no caching actor is available" in :
    val resolver = new MediaFileInfoResolverServiceImpl

    // Can only test that no exception is thrown.
    resolver.deactivate(mock)

  /**
    * A test helper class that manages a service to be tested and its
    * dependencies.
    */
  private class ResolverServiceTestHelper:
    /** The mock for the archive service. */
    private val archiveService = mock[ArchiveService]

    /** The message bus used by tests. */
    private val messageBus = new MessageBusTestImpl

    /** The actor factory used by this test class. */
    private val actorFactory = new TrackingActorFactory(implicitly)

    /** The service to be tested. */
    private val resolver = createResolver()

    /** Stores the result of the resolver callback. */
    private val refResult = new AtomicReference[Option[Try[Map[String, ArchiveModel.MediaFileInfo]]]](None)

    /**
      * Invokes the resolver service with the given IDs. The result passed to
      * the callback is recorded and can be queried using [[resolvedResult]].
      *
      * @param ids the IDs of media files to resolve
      */
    def resolveFileIDs(ids: Iterable[String]): ResolverServiceTestHelper =
      resolver.resolveFileIDs(ids): result =>
        refResult.set(Some(result))
      this

    /**
      * Returns the result of the last invocation of the [[resolveFileIDs]]
      * method of the service under test.
      *
      * @return the result delivered to the resolver callback
      */
    def resolvedResult: Try[Map[String, ArchiveModel.MediaFileInfo]] =
      refResult.get().getOrElse(fail("No resolver result available."))

    /**
      * Prepares the mock for the archive service to answer requests for the
      * given file IDs with the corresponding _MediaFileInfo_ objects. For
      * each entry in the map a request against the archive server REST API
      * for the file ID is expected. All other requests yield a failure result.
      *
      * @param fileInfo a map with file IDs and the info objects to return
      * @return this test helper
      */
    def expectFileInfoRequest(fileInfo: Map[String, ArchiveModel.MediaFileInfo]): ResolverServiceTestHelper =
      doReturn(Future.failed(new IllegalArgumentException("Test exception: Cannot resolve ID.")))
        .when(archiveService).queryData[ArchiveModel.MediaFileInfo](any())(using any())

      fileInfo.foreach:
        case (fileId, info) =>
          doReturn(Future.successful(info))
            .when(archiveService)
            .queryData[ArchiveModel.MediaFileInfo](argEq(s"/api/archive/files/$fileId/info"))(using any())
      this

    /**
      * Verifies that the archive service has been invoked exactly once for each
      * of the given file IDs.
      *
      * @param ids the IDs to check
      * @return this test helper
      */
    def verifyIDsRequested(ids: Iterable[String]): ResolverServiceTestHelper =
      ids.foreach: id =>
        verify(archiveService, times(1))
          .queryData[ArchiveModel.MediaFileInfo](argEq(s"/api/archive/files/$id/info"))(using any())
      this

    /**
      * Deactivates the test component and optionally expects that the actors
      * that have been created have been stopped again.
      *
      * @return this test helper
      */
    def deactivate(): ResolverServiceTestHelper =
      resolver.deactivate(mock)
      forEvery(actorFactory.typedActors.keySet): actorName =>
        actorFactory.expectTypedActorTerminated(actorName, typedTestKit)
      this

    /**
      * Expects that a message to synchronize a future with the UI thread has 
      * been published on the message bus and forwards this message.
      *
      * @return this test helper
      */
    def expectFutureMessage(): ResolverServiceTestHelper =
      val msg = messageBus.processNextMessage[AnyRef]()
      msg.getClass.getName should endWith("FutureUICallback")
      this

    /**
      * Creates a mock [[ClientApplicationContext]] to be used by the test
      * service.
      *
      * @return the mock client context
      */
    private def createClientContext(): ClientApplicationContext =
      val clientContext = mock[ClientApplicationContext]
      when(clientContext.actorSystem).thenReturn(system)
      when(clientContext.messageBus).thenReturn(messageBus)
      when(clientContext.actorFactory).thenReturn(actorFactory)
      clientContext

    /**
      * Creates the resolver service to be tested.
      *
      * @return the test service instance
      */
    private def createResolver(): MediaFileInfoResolverServiceImpl =
      val resolver = new MediaFileInfoResolverServiceImpl
      resolver.initClientContext(createClientContext())
      resolver.initArchiveService(archiveService)
      resolver.activate(mock)
      resolver
