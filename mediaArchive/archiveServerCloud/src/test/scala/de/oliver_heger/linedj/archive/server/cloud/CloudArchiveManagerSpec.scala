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

import de.oliver_heger.linedj.archive.cloud.{ArchiveCryptConfig, CloudArchiveConfig, CloudFileDownloader, CloudFileDownloaderFactory}
import de.oliver_heger.linedj.archive.cloud.auth.{BasicAuthMethod, Credentials}
import de.oliver_heger.linedj.archive.server.cloud.CloudArchiveManagerSpec.{TestArchiveIndex, TestArchiveName, TestServerConfig}
import de.oliver_heger.linedj.archive.server.model.ArchiveCommands
import de.oliver_heger.linedj.shared.actors.{ActorFactory, ManagingActorFactory}
import de.oliver_heger.linedj.shared.actors.ActorFactory.executionContext
import org.apache.pekko.Done
import org.apache.pekko.actor.{ActorSystem, typed}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, anyInt, eq as argEq}
import org.mockito.Mockito.*
import org.scalatest.{BeforeAndAfterAll, TryValues}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
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
      optCryptConfig = Some(ArchiveCryptConfig(100 + index, 64 + index))
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
        .waitForLoadedStatus()
        .verifyContentLoaded()

  it should "handle a failure when starting a cloud archive" in :
    val loadException = new IllegalStateException("Test exception: Cannot load archive.")
    archiveManagerTest: helper =>
      helper.startTestArchive(optFailure = Some(loadException))
        .waitForStatus(CloudArchiveManager.CloudArchiveState.Failure(loadException, 1))

  it should "reset credentials after a failed start of an archive" in :
    val loadException = new IllegalStateException("Test exception: Invalid credentials for archive.")
    archiveManagerTest: helper =>
      helper.startTestArchive(optFailure = Some(loadException))
        .waitForStatus(CloudArchiveManager.CloudArchiveState.Failure(loadException, 1))
        .verifyCredentialsReset()

  it should "support further attempts to start an archive after a failure" in :
    val loadException1 = new IllegalStateException("Test exception: Failure (1).")
    val loadException2 = new IllegalStateException("Test exception: Repeated failure to load archive.")
    archiveManagerTest: helper =>
      helper.startTestArchive(optFailure = Some(loadException1))
        .waitForStatus(CloudArchiveManager.CloudArchiveState.Failure(loadException1, 1))
        .startTestArchive(optFailure = Some(loadException2))
        .waitForStatus(CloudArchiveManager.CloudArchiveState.Failure(loadException2, 2))

  /**
    * A test helper class managing an instance under test and its 
    * dependencies.
    */
  private class ArchiveManagerTestHelper extends AutoCloseable:
    /**
      * The actor factory used by tests. It needs to be cleaned up after each
      * test case to make sure that the archive manager actor gets stopped.
      */
    private val actorFactory = new ActorFactoryWaitForTermination

    /** Mock for the content actor. */
    private val contentActor = mock[ActorRef[ArchiveCommands.UpdateArchiveContentCommand]]

    /** The mock for the downloader factory. */
    private val downloaderFactory = createDownloaderFactory()

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

    /**
      * A reference to the promise that yields the downloader for the test
      * archive. This is returned by the stub implementation of the downloader
      * factory, so that it is possible to update the promise dynamically. This
      * allows testing multiple attempts to start the archive.
      */
    private val refPromiseDownloader = AtomicReference(Promise[CloudFileDownloader]())

    /** The archive manager under test. */
    private val archiveManager = createArchiveManager()

    override def close(): Unit =
      actorFactory.close()

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
      when(cacheFactory.apply(TestServerConfig.cacheDirectory, TestArchiveName)).thenReturn(cache)
      when(contentLoader.loadContent(any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(Future.successful(Done))

      val promiseDownloader = refPromiseDownloader.get()
      // Set another promise to allow further attempts to load the test archive.
      refPromiseDownloader.set(Promise())
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
      * Verifies that after a failed attempt to start the test archive, its
      * credentials are reset.
      *
      * @return this test helper
      */
    def verifyCredentialsReset(): ArchiveManagerTestHelper =
      val testArchiveConfig = TestServerConfig.archives(TestArchiveIndex)
      verify(credentialSetter).clearCredential(testArchiveConfig.authMethod.realm + ".username")
      verify(credentialSetter).clearCredential(testArchiveConfig.authMethod.realm + ".password")
      verify(credentialSetter).clearCredential(TestArchiveName)
      this

    /**
      * Waits until the expected state is reached for the test archive.
      *
      * @param expectedState the expected state
      * @return this test helper
      */
    def waitForStatus(expectedState: CloudArchiveManager.CloudArchiveState): ArchiveManagerTestHelper =
      waitForStatus(_ == expectedState)

    /**
      * Waits until the archive state indicates that the test archive has been
      * loaded.
      *
      * @return this test helper
      */
    def waitForLoadedStatus(): ArchiveManagerTestHelper =
      waitForStatus: state =>
        state match
          case CloudArchiveManager.CloudArchiveState.Loaded(loader) if loader.fileDownloader == downloader =>
            true
          case _ => false

    /**
      * Waits until a state is reached for the test archive for which the given
      * comparison function returns '''true'''.
      *
      * @param comparison the comparison function
      * @return this test helper
      */
    private def waitForStatus(comparison: CloudArchiveManager.CloudArchiveState => Boolean): ArchiveManagerTestHelper =
      def checkStatus(): Future[Boolean] =
        archiveManager.archivesState.flatMap: state =>
          if comparison(state.state(TestArchiveName)) then
            Future.successful(true)
          else
            checkStatus()

      val refStateFound = new AtomicBoolean
      checkStatus().foreach(refStateFound.set)
      awaitCond(refStateFound.get())
      this

    /**
      * Returns a stub implementation for a downloader factory that returns a 
      * specific [[Future]] for the test archive. The test helper allows 
      * setting this future, so that successful and failed starts of the test
      * archive can be simulated.
      *
      * @return the stub [[CloudFileDownloaderFactory]]
      */
    private def createDownloaderFactory(): CloudFileDownloaderFactory =
      (archiveConfig: CloudArchiveConfig) =>
        if archiveConfig.archiveName == TestArchiveName then
          refPromiseDownloader.get().future
        else
          Promise().future

    /**
      * Creates the archive manager to be tested.
      *
      * @return the test instance
      */
    private def createArchiveManager(): CloudArchiveManager =
      CloudArchiveManager.newInstance(
        actorFactory = actorFactory,
        contentActor = contentActor,
        config = TestServerConfig,
        downloaderFactory = downloaderFactory,
        credentialSetter = credentialSetter,
        contentLoader = contentLoader,
        cacheFactory = cacheFactory
      )
  end ArchiveManagerTestHelper

  /**
    * A specialized implementation of [[ActorFactory]] which makes sure that
    * the archive manager actor actually terminates. This is necessary to
    * prevent non-unique actor name exceptions.
    */
  private class ActorFactoryWaitForTermination extends ActorFactory, AutoCloseable:
    /** The underlying factory to create actors. */
    private val wrappedFactory = ManagingActorFactory.newDefaultManagingActorFactory

    /** Stores the single actor created by this factory. */
    private val refActor = new AtomicReference[ActorRef[?]]

    export wrappedFactory.{createTypedActor as oldCreateTypedActor, *}

    override def createTypedActor[T](behavior: Behavior[T],
                                     name: String,
                                     props: typed.Props = typed.Props.empty,
                                     optStopCommand: Option[T] = None): typed.ActorRef[T] =
      val newActor = wrappedFactory.createTypedActor(behavior, name, props, optStopCommand)
      refActor.compareAndSet(null, newActor) shouldBe true
      newActor

    /**
      * @inheritdoc This implementation stops all managed actors and waits for
      *             the termination of the archive manager actor.
      */
    override def close(): Unit =
      wrappedFactory.stopActors()
      val managerActor = refActor.get().toClassic
      val probe = TestProbe()
      probe.watch(managerActor)
      probe.expectTerminated(managerActor)
  end ActorFactoryWaitForTermination
