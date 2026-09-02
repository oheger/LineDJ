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
import de.oliver_heger.linedj.platform.startup.ConfigService
import de.oliver_heger.linedj.shared.actors.{ActorFactory, CachingActor, TrackingActorFactory}
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import org.apache.commons.configuration2.BaseHierarchicalConfiguration
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.testkit.TestKit
import org.mockito.ArgumentMatchers.{any, eq as argEq}
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
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

    helper.activate()
      .expectFileInfoRequests(fileInfo)
      .resolveFileIDs(fileInfo.keySet)
      .expectFutureMessage()
      .resolvedResult should be(Success(fileInfo))

    helper.deactivate()

  it should "return cached info objects on a repeated request" in :
    val fileInfo = createFileInfos(8)
    val helper = new ResolverServiceTestHelper

    helper.activate()
      .expectFileInfoRequests(fileInfo)
      .resolveFileIDs(fileInfo.keySet)
      .expectFutureMessage()
      .resolveFileIDs(fileInfo.keySet)
      .expectFutureMessage()
      .resolvedResult should be(Success(fileInfo))

    helper.verifyIDsRequested(fileInfo.keySet)
      .deactivate()

  it should "limit the size of the cache if configured" in :
    val fileInfo = createFileInfos(4)
    val helper = new ResolverServiceTestHelper

    helper.initConfigService(Map(MediaFileInfoResolverServiceImpl.PropertyCacheSize -> Integer.valueOf(2)))
      .activate()
      .expectFileInfoRequests(fileInfo)
      .resolveFileIDs(List(createFileID(0), createFileID(1)))
      .expectFutureMessage()
      .resolveFileIDs(fileInfo.keySet)
      .expectFutureMessage()
      .resolveFileIDs(List(createFileID(0), createFileID(1)))
      .expectFutureMessage()
      .resolvedResult should be(Success(Map(
      createFileID(0) -> fileInfo(createFileID(0)),
      createFileID(1) -> fileInfo(createFileID(1))
    )))

    // With a cache size of 2, adding the IDs 2 and 3 evicts the initially
    // fetched IDs 0 and 1; thus, they have to be queried again.
    helper.verifyIDsRequested(List(createFileID(0), createFileID(1)), occurrence = 2)
      .verifyIDsRequested(List(createFileID(2), createFileID(3)))
      .deactivate()

  it should "evaluate the parallelism property from the configuration" in :
    val helper = new ResolverServiceTestHelper

    helper.initConfigService(Map(MediaFileInfoResolverServiceImpl.PropertyQueryParallelism -> Integer.valueOf(3)))
      .activate()

    helper.parallelLimit should be(Some(3))
    helper.deactivate()

  it should "not impose a parallelism limit if not configured" in :
    val helper = new ResolverServiceTestHelper

    helper.activate()

    helper.parallelLimit should be(None)
    helper.deactivate()

  it should "handle a failure to resolve a file ID" in :
    val helper = new ResolverServiceTestHelper

    helper.activate()
      .expectFileInfoRequests(createFileInfos(4))
      .resolveFileIDs(List(createFileID(1), createFileID(2), "some-other-ID", createFileID(3)))
      .expectFutureMessage()
      .deactivate()
      .resolvedResult.isFailure shouldBe true

  it should "handle a deactivate call if no caching actor is available" in :
    val resolver = new MediaFileInfoResolverServiceImpl

    // Can only test that no exception is thrown.
    resolver.deactivate(mock)

  it should "evaluate the timeout property from the configuration" in :

    given ExecutionContext = system.dispatcher

    val fileID = createFileID(42)
    val futInfo = Future:
      Thread.sleep(1000)
      createFileInfo(42)
    val helper = new ResolverServiceTestHelper

    helper.initConfigService(Map(MediaFileInfoResolverServiceImpl.PropertyCacheQueryTimeout -> "100ms"))
      .activate()
      .expectFileInfoRequest(fileID, futInfo)
      .resolveFileIDs(List(fileID))
      .expectFutureMessage()
      .resolvedResult.isFailure shouldBe true

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

    /**
      * Stores the parallelism limit that was passed to the caching actor
      * factory during activation.
      */
    private val refParallelism = new AtomicReference[Option[Int]]

    /**
      * A stub for the caching actor factory that delegates to the default
      * factory, but records the parallelism limit of the last invocation.
      */
    private val cachingActorFactory = new CachingActor.Factory:
      override def apply[K, V](resolver: CachingActor.KeyResolverFunc[K, V],
                               store: CachingActor.Store[K, V] = CachingActor.mapStore[K, V],
                               parallelLimit: Option[Int] = None): Behavior[CachingActor.CacheCommand[K, V]] =
        refParallelism.set(parallelLimit)
        CachingActor.newInstance(resolver, store, parallelLimit)

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
      * Activates the service under test. This is done explicitly by the test
      * cases so that they have the chance to configure dependencies before the
      * actual activation.
      *
      * @return this test helper
      */
    def activate(): ResolverServiceTestHelper =
      resolver.activate(mock)
      this

    /**
      * Initializes the configuration service of the service under test with a
      * configuration that is created from the given properties. The
      * configuration is exposed by a mock [[ConfigService]] which is passed to
      * the resolver. This method must be called before [[activate]] to take
      * effect.
      *
      * @param properties a map with configuration properties
      * @return this test helper
      */
    def initConfigService(properties: Map[String, AnyRef]): ResolverServiceTestHelper =
      val configService = createConfigService(properties)
      resolver.initConfigService(configService)
      this

    /**
      * Records the parallelism limit passed to the caching actor factory by
      * the last activation.
      *
      * @return an [[Option]] with the last parallelism limit
      */
    def parallelLimit: Option[Int] = refParallelism.get()

    /**
      * Prepares the mock for the archive service to answer requests for the
      * given file IDs with the corresponding _MediaFileInfo_ objects. For
      * each entry in the map a request against the archive server REST API
      * for the file ID is expected. All other requests yield a failure result.
      *
      * @param fileInfo a map with file IDs and the info objects to return
      * @return this test helper
      */
    def expectFileInfoRequests(fileInfo: Map[String, ArchiveModel.MediaFileInfo]): ResolverServiceTestHelper =
      doReturn(Future.failed(new IllegalArgumentException("Test exception: Cannot resolve ID.")))
        .when(archiveService).queryData[ArchiveModel.MediaFileInfo](any())(using any())

      fileInfo.foreach:
        case (fileId, info) =>
          expectFileInfoRequest(fileId, Future.successful(info))
      this

    /**
      * Prepares the mock for the archive service to answer a single request
      * for a file ID with a specific result.
      *
      * @param id      the file ID in question
      * @param futInfo the [[Future]] with the query result
      * @return this test helper
      */
    def expectFileInfoRequest(id: String, futInfo: Future[ArchiveModel.MediaFileInfo]): ResolverServiceTestHelper =
      doReturn(futInfo).when(archiveService)
        .queryData[ArchiveModel.MediaFileInfo](argEq(s"/api/archive/files/$id/info"))(using any())
      this

    /**
      * Verifies that the archive service has been invoked exactly the
      * specified number of times for each of the given file IDs.
      *
      * @param ids        the IDs to check
      * @param occurrence the number of invocations to expect for each ID
      * @return this test helper
      */
    def verifyIDsRequested(ids: Iterable[String], occurrence: Int = 1): ResolverServiceTestHelper =
      ids.foreach: id =>
        verify(archiveService, times(occurrence))
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
      * Creates a [[ConfigService]] mock that returns a configuration populated
      * from the properties in the specified map.
      *
      * @param properties the map with configuration properties
      * @return the [[ConfigService]] mock returning this configuration
      */
    private def createConfigService(properties: Map[String, AnyRef]): ConfigService =
      val config = new BaseHierarchicalConfiguration
      properties.foreach:
        case (key, value) => config.addProperty(key, value)
      val configService = mock[ConfigService]
      when(configService.config).thenReturn(config)
      configService

    /**
      * Creates the resolver service to be tested. Note that the service is
      * not activated; activation must be triggered by the test cases relying
      * on [[activate]].
      *
      * @return the test service instance
      */
    private def createResolver(): MediaFileInfoResolverServiceImpl =
      val resolver = new MediaFileInfoResolverServiceImpl(cachingActorFactory)
      resolver.initClientContext(createClientContext())
      resolver.initArchiveService(archiveService)
      resolver.initConfigService(createConfigService(Map.empty))
      resolver
