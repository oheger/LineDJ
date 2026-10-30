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

package de.oliver_heger.linedj.platform.audio.playlist.persistence

import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.audio2.AudioPlayerCommands
import de.oliver_heger.linedj.platform.audio2.playlist.{MediaFileInfoResolverService, Playlist}
import de.oliver_heger.linedj.shared.actors.{ActorFactory, BackoffActor}
import org.apache.pekko.actor as classics
import org.apache.pekko.actor.DeadLetter
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Props}
import org.mockito.Mockito.*
import org.mockito.{ArgumentCaptor, Mockito}
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}
import scala.compiletime.uninitialized
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

object PlaylistLoaderActorSpec:
  /** Test configuration used by the handler. */
  private val HandlerConfig = PlaylistHandlerConfig(
    pathPlaylist = Paths.get("playlist.lst"),
    pathPosition = Paths.get("position.pos"),
    checkPlaylistMinBackoff = 100.millis,
    checkPlaylistMaxBackoff = 5.minutes,
    checkPlaylistFactor = 1.5,
    maxFileSize = 10000,
    autoSaveInterval = 90.seconds,
    shutdownTimeout = 30.seconds
  )

  /** The configuration expected to be passed to the loader function. */
  private val LoaderConfig = PlaylistLoader.Config(
    playlistPath = HandlerConfig.pathPlaylist,
    positionPath = HandlerConfig.pathPosition,
    maxFileSize = HandlerConfig.maxFileSize
  )

  /** The songs in the test playlist. */
  private val PlaylistSongs = List("song1", "song2", "song3", "song4")

  /** The command to set the playlist with the test songs. */
  private val SetPlaylistCommand = AudioPlayerCommands.SetPlaylist(
    playlist = Playlist(pendingSongs = PlaylistSongs.take(3), PlaylistSongs.drop(3))
  )
end PlaylistLoaderActorSpec

/**
  * Test class for [[PlaylistLoaderActor]].
  */
class PlaylistLoaderActorSpec extends ScalaTestWithActorTestKit, AsyncFlatSpecLike, Matchers, MockitoSugar:

  import PlaylistLoaderActorSpec.*

  "A PlaylistLoaderActor" should "handle a close before the playlist is loaded" in :
    val playerCommand = AudioPlayerCommands.StartAudioPlayback
    val probeDeadLetters = testKit.createDeadLetterProbe()
    val helper = new LoaderActorTestHelper

    helper.stopTestActor()
      .setLoadPlaylistResult(Some(playerCommand))

    probeDeadLetters.expectMessageType[DeadLetter]
    succeed

  it should "set a playlist when it is available" in :
    val helper = new LoaderActorTestHelper

    val futTaskResult = helper.setLoadPlaylistResult(Some(SetPlaylistCommand))
      .expectBackoffActorCreation()
      .triggerBackoffTaskFunc()

    helper.expectAndAnswerResolverRequest(Success(Map.empty))
      .expectPlaylistPublished()
    futTaskResult map (result => result should be(BackoffActor.TaskResult.Cancel))

  it should "close the backoff actor when it is stopped" in :
    val helper = new LoaderActorTestHelper

    helper.setLoadPlaylistResult(Some(SetPlaylistCommand))
      .expectBackoffActorCreation()
      .stopTestActor()
      .verifyBackoffHandleClosed()
    succeed

  it should "not set a playlist when songs cannot be resolved" in :
    val helper = new LoaderActorTestHelper

    val futTaskResult = helper.setLoadPlaylistResult(Some(SetPlaylistCommand))
      .expectBackoffActorCreation()
      .triggerBackoffTaskFunc()

    helper.expectAndAnswerResolverRequest(Failure(new IllegalStateException("Could not resolve media files.")))
      .expectNoPlaylistPublished()
    futTaskResult map (result => result should be(BackoffActor.TaskResult.Backoff))

  it should "not continue with resolving songs if no playlist was loaded" in :
    val helper = new LoaderActorTestHelper

    helper.setLoadPlaylistResult(None)
      .expectEmptyPlaylistPublished()
      .expectNoBackoffActorCreation()
    succeed

  it should "not continue with resolving songs if loading the playlist failed" in :
    val helper = new LoaderActorTestHelper

    helper.setLoadPlaylistFailure()
      .expectEmptyPlaylistPublished()
      .expectNoBackoffActorCreation()
    succeed

  it should "not continue with resolving songs if an unexpected result is returned from the load function" in :
    val helper = new LoaderActorTestHelper

    helper.setLoadPlaylistResult(Some(AudioPlayerCommands.StartAudioPlayback))
      .expectEmptyPlaylistPublished()
      .expectNoBackoffActorCreation()
    succeed

  /**
    * A test helper class managing a test instance and its dependencies.
    */
  private class LoaderActorTestHelper:
    /** The test message bus. */
    private val messageBus = new MessageBusTestImpl

    /**
      * A promise that is used to define the result for the playlist loader 
      * function. Setting this promise causes the loader function to return the
      * corresponding value.
      */
    private val promiseLoader = Promise[Option[AudioPlayerCommands]]()

    /** The actor factory used by the tests. */
    private val testActorFactory = createActorFactory()

    /** The handle of the simulated backoff actor. */
    private val backoffHandle = mock[BackoffActor.BackoffHandle]

    /**
      * A queue that is used to obtain the task function that was passed to the
      * backoff actor factory.
      */
    private val queueTaskFunc = new ArrayBlockingQueue[BackoffActor.TaskFunc](1)

    /** The mock for the resolver service. */
    private val resolverService = mock[MediaFileInfoResolverService]

    /** The handle to close the test actor. */
    private val actorHandle = createLoaderActor()

    /** Stores the task function passed to the backoff actor. */
    private var backoffTaskFunc: BackoffActor.TaskFunc = uninitialized

    /**
      * Stops the test actor instance.
      *
      * @return this test helper
      */
    def stopTestActor(): LoaderActorTestHelper =
      actorHandle.close()
      this

    /**
      * Sets the result that should be returned by the test playlist loader
      * function.
      *
      * @param optResult the result of the playlist loader function
      * @return this test helper
      */
    def setLoadPlaylistResult(optResult: Option[AudioPlayerCommands]): LoaderActorTestHelper =
      promiseLoader.success(optResult)
      this

    /**
      * Sets a failure result for the playlist loader function.
      *
      * @return this test helper
      */
    def setLoadPlaylistFailure(): LoaderActorTestHelper =
      promiseLoader.failure(new IllegalStateException("Test exception: Could not load playlist."))
      this

    /**
      * Expects that a backoff actor instance has been created. The function 
      * also obtains the task function passed to the backoff actor and stores 
      * it in an internal field, so that it can be triggered.
      *
      * @return this test helper
      */
    def expectBackoffActorCreation(): LoaderActorTestHelper =
      backoffTaskFunc = queueTaskFunc.poll(3, TimeUnit.SECONDS)
      backoffTaskFunc should not be null
      this

    /**
      * Expects that no backoff actor has been created.
      *
      * @return this test helper
      */
    def expectNoBackoffActorCreation(): LoaderActorTestHelper =
      queueTaskFunc.poll(100, TimeUnit.MILLISECONDS) should be(null)
      this

    /**
      * Invokes the task function passed to the backoff actor. Before this
      * function [[expectBackoffActorCreation()]] must be called.
      *
      * @return the [[Future]] result from the task function
      */
    def triggerBackoffTaskFunc(): Future[BackoffActor.TaskResult] = backoffTaskFunc()

    /**
      * Expects that the resolver service was called for the songs of the test
      * playlist and answers the call with the given result.
      *
      * @param result the result for the service
      * @return this test helper
      */
    def expectAndAnswerResolverRequest(result: Try[Map[String, ArchiveModel.MediaFileInfo]]): LoaderActorTestHelper =
      val captSongs = ArgumentCaptor.forClass(classOf[Iterable[String]])
      val captCallback = ArgumentCaptor.forClass(classOf[Try[Map[String, ArchiveModel.MediaFileInfo]] => Unit])
      verify(resolverService, Mockito.timeout(3000)).resolveFileIDs(captSongs.capture())(captCallback.capture())
      captSongs.getValue should contain theSameElementsAs PlaylistSongs
      captCallback.getValue.apply(result)
      this

    /**
      * Expects that a command to set a playlist was published on the system
      * message bus.
      *
      * @param expectedPlaylist the expected playlist
      * @return this test helper
      */
    def expectPlaylistPublished(expectedPlaylist: AudioPlayerCommands = SetPlaylistCommand): LoaderActorTestHelper =
      messageBus.expectMessageType[AudioPlayerCommands] should be(expectedPlaylist)
      this

    /**
      * Expects that no command to set the test playlist was published on the
      * system message bus for a certain period.
      *
      * @return this test helper
      */
    def expectNoPlaylistPublished(): LoaderActorTestHelper =
      messageBus.expectNoMessage(100.millis)
      this

    /**
      * Expects that a command to set an empty playlist was published on the
      * system message bus. Such a command needs to be sent when no playlist
      * could be loaded for whatever reason.
      *
      * @return this test helper
      */
    def expectEmptyPlaylistPublished(): LoaderActorTestHelper =
      val emptyPlaylist = AudioPlayerCommands.SetPlaylist(Playlist(Nil, Nil), closePlaylist = false)
      expectPlaylistPublished(emptyPlaylist)

    /**
      * Verifies that the handle to the backoff actor was closed.
      *
      * @return this test helper
      */
    def verifyBackoffHandleClosed(): LoaderActorTestHelper =
      verify(backoffHandle, Mockito.timeout(3000)).close()
      this

    /**
      * Creates an [[ActorFactory]] to be used by the tests to create new actor
      * instances. The factory is only used to create new instances of the 
      * actor class under test. It makes sure that no name conflicts can occur.
      *
      * @return the test actor factory
      */
    private def createActorFactory(): ActorFactory =
      new ActorFactory:
        override def actorSystem: classics.ActorSystem = system.toClassic

        override def createClassicActor(props: classics.Props,
                                        name: String,
                                        optStopCommand: Option[Any]): classics.ActorRef =
          throw new UnsupportedOperationException("Unexpected invocation of createClassicActor.")

        override def createTypedActor[T](behavior: Behavior[T],
                                         name: String,
                                         props: Props,
                                         optStopCommand: Option[T]): ActorRef[T] =
          name should be(PlaylistLoaderActor.ActorName)
          optStopCommand shouldBe empty
          testKit.spawn(behavior)

    /**
      * Returns the function to load the playlist to be used by a test actor
      * instance. The function checks the provided configuration and returns
      * the [[Future]] of the promise that is used to define the result.
      *
      * @return the playlist loader function
      */
    private def createLoaderFunc(): PlaylistLoader.LoaderFunc =
      config =>
        config should be(LoaderConfig)
        promiseLoader.future

    /**
      * Creates a factory for the backoff actor used for testing. The factory 
      * checks the provided parameters and passes the task function to a queue,
      * so that it can be obtained and tested later.
      *
      * @return the test factory for the backoff actor
      */
    private def createBackoffFactory(): BackoffActor.Factory =
      new BackoffActor.Factory:
        override def apply(params: BackoffActor.BackoffParameters, name: String)
                          (using actorFactory: ActorFactory): BackoffActor.BackoffHandle =
          actorFactory should be(testActorFactory)
          name should be(PlaylistLoaderActor.BackoffActorName)
          params.minBackoff should be(HandlerConfig.checkPlaylistMinBackoff)
          params.maxBackoff should be(HandlerConfig.checkPlaylistMaxBackoff)
          params.incrementFactor should be(HandlerConfig.checkPlaylistFactor)
          params.failureResult should be(BackoffActor.TaskResult.Backoff)
          queueTaskFunc.offer(params.taskFunc)
          backoffHandle

    /**
      * Returns an object with parameters to create a new test actor instance.
      *
      * @return the parameters for the test instance
      */
    private def createLoaderParams(): PlaylistLoaderActor.Params =
      PlaylistLoaderActor.Params(
        config = HandlerConfig,
        messageBus = messageBus,
        loaderFunc = createLoaderFunc(),
        resolverService = resolverService,
        backoffFactory = createBackoffFactory()
      )

    /**
      * Creates the test actor instance and returns the handle to close it.
      *
      * @return the handle to close the test actor
      */
    private def createLoaderActor(): AutoCloseable =
      PlaylistLoaderActor.newInstance(createLoaderParams())(using testActorFactory)
