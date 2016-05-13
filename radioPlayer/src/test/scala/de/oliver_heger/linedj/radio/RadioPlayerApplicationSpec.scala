/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.radio

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ArrayBlockingQueue, ConcurrentHashMap, TimeUnit}

import akka.pattern.AskTimeoutException
import akka.util.Timeout
import de.oliver_heger.linedj.client.app.{ApplicationAsyncStartup, ApplicationSyncStartup, ApplicationTestSupport, ClientApplicationContext}
import de.oliver_heger.linedj.io.CloseAck
import de.oliver_heger.linedj.player.engine.PlaybackContextFactory
import de.oliver_heger.linedj.player.engine.facade.RadioPlayer
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.window.Window
import org.mockito.Matchers.{eq => eqArg, _}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Promise}
import scala.util.Random

/**
  * Test class for ''RadioPlayerApplication''.
  */
class RadioPlayerApplicationSpec extends FlatSpec with Matchers with MockitoSugar with
  ApplicationTestSupport {
  "A RadioPlayerApplication" should "define a correct default constructor" in {
    val app = new RadioPlayerApplication

    app.playerFactory should not be null
    app shouldBe a[ApplicationAsyncStartup]
    app.configName should be("radioplayer_config.xml")
  }

  it should "create a bean for the radio player" in {
    val helper = new RadioPlayerApplicationTestHelper
    val app = helper.activateRadioApp()

    queryBean[RadioPlayer](app, "radioApp_player") should be(helper.player)
  }

  it should "add playback context factories arrived after creation to the player" in {
    val helper = new RadioPlayerApplicationTestHelper
    helper.activateRadioApp()

    val factories = helper addPlaybackContextFactories 8
    helper.addedPlaybackContextFactories should contain theSameElementsAs factories
  }

  it should "remove playback context factories from the player after startup" in {
    val helper = new RadioPlayerApplicationTestHelper
    helper.activateRadioApp()

    val factoriesAdded = helper addPlaybackContextFactories 8
    val part = Random.shuffle(factoriesAdded) splitAt 4
    part._1 foreach helper.app.removePlaylistContextFactory
    helper.addedPlaybackContextFactories should contain theSameElementsAs part._2
  }

  it should "add playback context factories arrived before creation of the player" in {
    val helper = new RadioPlayerApplicationTestHelper
    val factories = helper addPlaybackContextFactories 8
    helper.activateRadioApp()

    helper.addedPlaybackContextFactories should contain theSameElementsAs factories
  }

  it should "remove playback context factories before the creation of the player" in {
    val helper = new RadioPlayerApplicationTestHelper
    val factoriesAdded = helper addPlaybackContextFactories 8
    val part = Random.shuffle(factoriesAdded) splitAt 4
    part._1 foreach helper.app.removePlaylistContextFactory
    helper.activateRadioApp()

    helper.addedPlaybackContextFactories should contain theSameElementsAs part._2
  }

  it should "correctly synchronize adding playlist context factories" in {
    val helper = new RadioPlayerApplicationTestHelper
    val queue = new ArrayBlockingQueue[Seq[PlaybackContextFactory]](1)
    val thread = new Thread {
      override def run(): Unit = {
        Thread sleep 500
        queue.put(helper addPlaybackContextFactories 2000)
      }
    }
    thread.start()
    helper.activateRadioApp()
    thread.join(5000)

    val factories = queue.poll(2, TimeUnit.SECONDS)
    helper.addedPlaybackContextFactories should contain theSameElementsAs factories
  }

  it should "correctly synchronize removing playlist context factories" in {
    val helper = new RadioPlayerApplicationTestHelper
    val factories = helper addPlaybackContextFactories 2500
    val thread = new Thread {
      override def run(): Unit = {
        Thread sleep 500
        factories foreach helper.app.removePlaylistContextFactory
      }
    }
    thread.start()
    helper.activateRadioApp()
    thread.join(5000)

    helper.addedPlaybackContextFactories should have size 0
  }

  it should "close the player on shutdown" in {
    val helper = new RadioPlayerApplicationTestHelper
    helper.activateRadioApp()

    val promise = Promise.successful(Seq.empty[CloseAck])
    helper.shutDownTest(promise)
    verify(helper.player).close()(any(classOf[ExecutionContextExecutor]), eqArg(Timeout(3.seconds)))
  }

  it should "not crash on shutdown if there is no radio player" in {
    val helper = new RadioPlayerApplicationTestHelper

    helper.app.closePlayer()
  }

  it should "wait until the player terminates" in {
    val helper = new RadioPlayerApplicationTestHelper
    helper.activateRadioApp()
    val timestamp = new AtomicLong
    val promise = Promise[Seq[CloseAck]]()
    val thread = new Thread {
      override def run(): Unit = {
        Thread sleep 400
        timestamp.set(System.nanoTime())
        promise.success(List.empty)
      }
    }
    thread.start()

    helper.shutDownTest(promise)
    val shutdownComplete = System.nanoTime()
    thread.join(2000)
    shutdownComplete should be > timestamp.get()
  }

  it should "ignore exceptions when closing the player" in {
    val helper = new RadioPlayerApplicationTestHelper
    helper.activateRadioApp()

    val promise = Promise.failed[Seq[CloseAck]](new AskTimeoutException("Test timeout exception"))
    helper.shutDownTest(promise)
  }

  it should "create a correct bean for the radio controller" in {
    val helper = new RadioPlayerApplicationTestHelper(mockUI = false)
    val app = helper.activateRadioApp()

    val ctrl = queryBean[RadioController](app.getMainWindowBeanContext, "radioController")
    ctrl.player should be(helper.player)
    ctrl.config should be(app.getUserConfiguration)
  }

  /**
    * A test helper class managing dependencies of a test instance and
    * providing some useful functionality.
    *
    * @param mockUI a flag whether the UI should be skipped
    */
  private class RadioPlayerApplicationTestHelper(mockUI: Boolean = true) {
    /** A mock for the radio player. */
    val player = createPlayerMock()

    /** A mock for the radio player factory. */
    val playerFactory = createPlayerFactory(player)

    /** The application to be tested. */
    val app = new RadioPlayerApplication(playerFactory) with ApplicationSyncStartup {
      override def initGUI(appCtx: ApplicationContext): Unit = {
        if (!mockUI) {
          super.initGUI(appCtx)
        }
      }

      override def showMainWindow(window: Window): Unit = {}

      override def onShutdown(): Unit = super.onShutdown()
    }

    /**
      * Stores the ''PlaybackContextFactory'' objects that were passed to the
      * player mock.
      */
    private val playbackContextFactories = new ConcurrentHashMap[PlaybackContextFactory, Boolean]

    /**
      * Starts up the test application.
      *
      * @return the test application
      */
    def activateRadioApp(): RadioPlayerApplication = activateApp(app)

    /**
      * Creates the given number of ''PlaybackContextFactory'' mocks and adds
      * them to the test application.
      *
      * @param count the number of factories to be added
      * @return a sequence with the mock factories that have been created
      */
    def addPlaybackContextFactories(count: Int): Seq[PlaybackContextFactory] = {
      val factories = (1 to count).map(i => mock[PlaybackContextFactory])
      factories foreach app.addPlaylistContextFactory
      factories
    }

    /**
      * Returns a set with the playback context factories that have been
      * added to the radio player.
      *
      * @return the set with added ''PlaybackContextFactory'' objects
      */
    def addedPlaybackContextFactories: Set[PlaybackContextFactory] = {
      import collection.JavaConverters._
      playbackContextFactories.keySet().asScala.toSet
    }

    /**
      * Triggers a test for a shutdown of the application.
      *
      * @param p the promise for the future to be returned by the player
      */
    def shutDownTest(p: Promise[Seq[CloseAck]]): Unit = {
      val ec = mock[ExecutionContextExecutor]
      when(app.clientApplicationContext.actorSystem.dispatcher).thenReturn(ec)
      when(player.close()(ec, Timeout(3.seconds))).thenReturn(p.future)
      app.shutdown()
    }

    /**
      * Creates a mock for the radio player. The mock handles the methods for
      * adding or removing playback context factories by updating the
      * corresponding map.
      *
      * @return the mock radio player
      */
    private def createPlayerMock(): RadioPlayer = {
      val player = mock[RadioPlayer]
      doAnswer(new Answer[AnyRef] {
        override def answer(invocation: InvocationOnMock): AnyRef = {
          val pcf = invocation.getArguments.head.asInstanceOf[PlaybackContextFactory]
          playbackContextFactories.put(pcf, java.lang.Boolean.TRUE)
          null
        }
      }).when(player).addPlaybackContextFactory(any(classOf[PlaybackContextFactory]))
      doAnswer(new Answer[AnyRef] {
        override def answer(invocation: InvocationOnMock): AnyRef = {
          val pcf = invocation.getArguments.head.asInstanceOf[PlaybackContextFactory]
          playbackContextFactories remove pcf
          null
        }
      }).when(player).removePlaybackContextFactory(any(classOf[PlaybackContextFactory]))
      player
    }

    /**
      * Creates a mock for the radio player factory. The factory returns the
      * mock player. It also checks the provided parameter.
      *
      * @param playerMock the mock player to be returned by the factory
      * @return the mock player factory
      */
    private def createPlayerFactory(playerMock: RadioPlayer): RadioPlayerFactory = {
      val factory = mock[RadioPlayerFactory]
      when(factory.createRadioPlayer(any(classOf[ClientApplicationContext]))).thenAnswer(new
          Answer[RadioPlayer] {
        override def answer(invocation: InvocationOnMock): RadioPlayer = {
          invocation.getArguments.head should be(app.clientApplicationContext)
          app.clientApplicationContext should not be null
          playerMock
        }
      })
      factory
    }
  }

}

