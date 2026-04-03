/*
 * Copyright 2015-2026 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.archive.server.cloud

import de.oliver_heger.linedj.archive.cloud.{CloudArchiveConfig, CloudFileDownloader, CloudFileDownloaderFactory}
import de.oliver_heger.linedj.archive.cloud.auth.{BasicAuthMethod, Credentials}
import de.oliver_heger.linedj.archive.server.cloud.CloudArchiveManagerSpec.{TestArchiveIndex, TestArchiveName, TestServerConfig}
import de.oliver_heger.linedj.archive.server.model.ArchiveCommands
import de.oliver_heger.linedj.shared.actors.{ActorFactory, ManagingActorFactory}
import de.oliver_heger.linedj.shared.actors.ActorFactory.executionContext
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.testkit.TestKit
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, anyInt, eq as argEq}
import org.mockito.Mockito.*
import org.scalatest.{BeforeAndAfterAll, TryValues}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{Future, Promise}
import scala.util.Using

object CloudArchiveManagerSpec:
  /**
    * A test configuration for the archive server. In the tests, one of the
    * managed archives is started.
    */
  private val TestServerConfig = CloudArchiveServerConfig(
    credentialsDirectory = Paths.get("test", "credentials", "path"),
    cacheDirectory = Paths.get("test", "cache", "directory"),
    archives = (1 to 4).map(createArchiveConfig).toList
  )

  /** The index of the archive that is started. */
  private val TestArchiveIndex = 2

  /** The name of the test archive that is started. */
  private val TestArchiveName = TestServerConfig.archives(TestArchiveIndex).archiveName

  /**
    * Creates a configuration for a test archive.
    *
    * @param index the index of the archive
    * @return the configuration for this test archive
    */
  private def createArchiveConfig(index: Int): ArchiveConfig =
    ArchiveConfig(
      archiveBaseUri = s"https://archive$index.example.com/archive",
      archiveName = s"testArchive-$index",
      authMethod = BasicAuthMethod(s"realm-$index"),
      fileSystem = "webDav",
      optContentPath = None,
      optMediaPath = None,
      optMetadataPath = None,
      optParallelism = Some(index),
      optMaxContentSize = Some(10 * index),
      optRequestQueueSize = None,
      optTimeout = None,
      optCryptConfig = None
    )
end CloudArchiveManagerSpec

/**
  * Test class for [[CloudArchiveManager]].
  */
class CloudArchiveManagerSpec(testSystem: ActorSystem) extends TestKit(testSystem), AnyFlatSpecLike, BeforeAndAfterAll,
  Matchers, MockitoSugar, TryValues:
  def this() = this(ActorSystem("CloudArchiveManagerSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  /**
    * Executes a test that uses a test helper and makes sure that the helper
    * gets properly closed afterward.
    *
    * @param test the test to be executed
    */
  private def archiveManagerTest(test: ArchiveManagerTestHelper => Unit): Unit =
    Using(new ArchiveManagerTestHelper): helper =>
      test(helper)
    .success

  "A CloudArchiveManager" should "return the expected initial archive state" in :
    archiveManagerTest: helper =>
      helper.waitForStatus(CloudArchiveManager.CloudArchiveState.Waiting)

  it should "successfully start a cloud archive" in :
    archiveManagerTest: helper =>
      helper.startTestArchive()
        .waitForStatus(CloudArchiveManager.CloudArchiveState.Loaded)
        .verifyContentLoaded()

  /**
    * A test helper class managing an instance under test and its 
    * dependencies.
    */
  private class ArchiveManagerTestHelper extends AutoCloseable:
    /**
      * The actor factory used by tests. It needs to be cleaned up after each
      * test case to make sure that the archive manager actor gets stopped.
      */
    private val actorFactory = ManagingActorFactory.newDefaultManagingActorFactory

    /** Mock for the content actor. */
    private val contentActor = mock[ActorRef[ArchiveCommands.UpdateArchiveContentCommand]]

    /** The mock for the downloader factory. */
    private val downloaderFactory = mock[CloudFileDownloaderFactory]

    /** The mock for the downloader for the test archive. */
    private val downloader = mock[CloudFileDownloader]

    /** Mock for the credential setter. */
    private val credentialSetter = mock[Credentials.CredentialSetter]

    /** Mock for the content loader. */
    private val contentLoader = mock[CloudArchiveContentLoader]

    /** Mock for the cache factory. */
    private val cacheFactory = mock[CloudArchiveCache.Factory]

    /** Mock for the cache for the test archive. */
    private val cache = mock[CloudArchiveCache]

    /** The promise that yields the downloader for the test archive. */
    private val promiseDownloader = Promise[CloudFileDownloader]()

    /** The archive manager under test. */
    private val archiveManager = createArchiveManager()

    override def close(): Unit =
      actorFactory.stopActors()

    /**
      * Triggers the start of the test archive which can be either successful
      * or fail.
      *
      * @param optFailure an [[Option]] with an exception to simulate a failure
      *                   to start the archive
      *
      * @return this test helper
      */
    def startTestArchive(optFailure: Option[Throwable] = None): ArchiveManagerTestHelper =
      when(cacheFactory.apply(TestServerConfig.cacheDirectory.resolve(TestArchiveName))).thenReturn(cache)
      when(contentLoader.loadContent(any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(Future.successful(Done))

      optFailure match
        case Some(exception) => promiseDownloader.failure(exception)
        case None => promiseDownloader.success(downloader)
      this

    /**
      * Verifies that the content loader was correctly invoked to load the
      * content of the test archive.
      *
      * @return this test helper
      */
    def verifyContentLoaded(): ArchiveManagerTestHelper =
      val archiveConfig: CloudArchiveConfig = TestServerConfig.archives(TestArchiveIndex)
      val captDownloader = ArgumentCaptor.forClass(classOf[ContentDownloader])
      verify(contentLoader).loadContent(
        captDownloader.capture(),
        argEq(cache),
        argEq(TestArchiveName),
        argEq(contentActor),
        argEq(archiveConfig.parallelism),
        argEq(archiveConfig.maxContentSize)
      )
      captDownloader.getValue.archiveConfig should be(archiveConfig)
      captDownloader.getValue.fileDownloader should be(downloader)
      this

    /**
      * Waits until the expected state is reached for the test archive.
      *
      * @param expectedState the expected state
      * @return this test helper
      */
    def waitForStatus(expectedState: CloudArchiveManager.CloudArchiveState): ArchiveManagerTestHelper =
      def checkStatus(): Future[Boolean] =
        archiveManager.archivesState.flatMap: state =>
          if state.state(TestArchiveName) == expectedState then
            Future.successful(true)
          else
            checkStatus()

      val refStateFound = new AtomicBoolean
      checkStatus().foreach(refStateFound.set)
      awaitCond(refStateFound.get())
      this

    /**
      * Creates the archive manager to be tested.
      *
      * @return the test instance
      */
    private def createArchiveManager(): CloudArchiveManager =
      val unusedPromise = Promise[CloudFileDownloader]()
      TestServerConfig.archives foreach : archive =>
        val futDownloader = if archive.archiveName == TestArchiveName then promiseDownloader.future
        else unusedPromise.future
        when(downloaderFactory.createDownloader(archive)).thenReturn(futDownloader)
      CloudArchiveManager.newInstance(
        actorFactory = actorFactory,
        contentActor = contentActor,
        config = TestServerConfig,
        downloaderFactory = downloaderFactory,
        credentialSetter = credentialSetter,
        contentLoader = contentLoader,
        cacheFactory = cacheFactory
      )
