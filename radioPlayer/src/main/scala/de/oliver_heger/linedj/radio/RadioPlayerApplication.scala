/*
 * Copyright 2015-2021 The Developers Team.
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

import akka.stream.scaladsl.Sink
import akka.util.Timeout
import de.oliver_heger.linedj.platform.app.ShutdownHandler.ShutdownCompletionNotifier
import de.oliver_heger.linedj.platform.app.support.ActorManagement
import de.oliver_heger.linedj.platform.app.{ApplicationAsyncStartup, ClientApplication, ShutdownHandler}
import de.oliver_heger.linedj.platform.bus.Identifiable
import de.oliver_heger.linedj.player.engine.facade.RadioPlayer
import de.oliver_heger.linedj.player.engine.{PlaybackContextFactory, PlayerEvent}
import net.sf.jguiraffe.gui.app.ApplicationContext
import org.osgi.service.component.ComponentContext

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * The ''Application'' class for the radio player application.
  *
  * This class is a JGUIraffe application and a declarative services
  * component. It declares a dynamic reference to services of type
  * ''PlaybackContextFactory''. Such services are needed by the audio player
  * engine for creating correct audio streams.
  *
  * On startup, a bean for the radio player is created using the internal
  * ''RadioPlayerFactory'' helper class. The player object needs to be
  * initialized with the ''PlaybackContextFactory'' services registered in the
  * system. However, as such services can arrive (and disappear again) at any
  * time - even before the player is created -, the logic here is a bit tricky
  * and also requires proper synchronization.
  *
  * @param playerFactory the factory for creating a radio player
  */
class RadioPlayerApplication(private[radio] val playerFactory: RadioPlayerFactory) extends
  ClientApplication("radioplayer") with ApplicationAsyncStartup with ActorManagement with Identifiable
  with ShutdownHandler.ShutdownObserver {
  def this() = this(new RadioPlayerFactory)

  /** The radio player managed by this application. */
  private var player: Option[RadioPlayer] = None

  /**
    * A list for storing playback context factories that arrive before the
    * radio player was created.
    */
  private var pendingPlaybackContextFactories = List.empty[PlaybackContextFactory]

  /**
    * Adds a ''PlaybackContextFactory'' service to this application. This
    * factory has to be passed to the radio player. This method is called by
    * the declarative services runtime.
    *
    * @param factory the factory service to be added
    */
  def addPlaylistContextFactory(factory: PlaybackContextFactory): Unit = {
    this.synchronized {
      player match {
        case Some(p) => p addPlaybackContextFactory factory
        case None => pendingPlaybackContextFactories = factory :: pendingPlaybackContextFactories
      }
    }
  }

  /**
    * Removes a ''PlaybackContextFactory'' service from this application. This
    * operation is delegated to the radio player. This method is called by the
    * declarative services runtime.
    *
    * @param factory the factory service to be removed
    */
  def removePlaylistContextFactory(factory: PlaybackContextFactory): Unit = {
    this.synchronized {
      player match {
        case Some(p) => p removePlaybackContextFactory factory
        case None =>
          pendingPlaybackContextFactories = pendingPlaybackContextFactories filterNot (_ == factory)
      }
    }
  }

  /**
    * @inheritdoc This implementation creates the radio player and stores it in
    *             the global bean context.
    */
  override def createApplicationContext(): ApplicationContext = {
    val context = super.createApplicationContext()
    val playerBean = playerFactory.createRadioPlayer(this)
    playerBean registerEventSink createPlayerListenerSink(playerBean)
    initPlayer(playerBean)
    addBeanDuringApplicationStartup("radioApp_player", playerBean)
    context
  }

  /**
    * @inheritdoc This implementation adds a bean for the user configuration
    *             to the central bean context.
    */
  override def initGUI(appCtx: ApplicationContext): Unit = {
    addBeanDuringApplicationStartup("radioApp_config", getUserConfiguration)
    super.initGUI(appCtx)
  }

  /**
    * @inheritdoc This implementation performs some registrations.
    */
  override def activate(compContext: ComponentContext): Unit = {
    super.activate(compContext)

    clientApplicationContext.messageBus.publish(ShutdownHandler.RegisterShutdownObserver(componentID, this))
  }

  /**
    * @inheritdoc This implementation closes the player.
    */
  override def deactivate(componentContext: ComponentContext): Unit = {
    super.deactivate(componentContext)
  }

  /**
    * @inheritdoc Triggers the shutdown of the radio player application.
    */
  override def triggerShutdown(completionNotifier: ShutdownHandler.ShutdownCompletionNotifier): Unit = {
    closePlayer(completionNotifier)
  }

  /**
    * Closes the radio player and waits for its termination.
    *
    * @param completionNotifier the notifier for a completed shutdown
    */
  private[radio] def closePlayer(completionNotifier: ShutdownCompletionNotifier): Unit = {
    val optPlayer = this.synchronized(player)
    optPlayer.foreach { p =>
      p.stopPlayback()
      val f = p.close()(clientApplicationContext.actorSystem.dispatcher, Timeout(3.seconds))
      try {
        log.info("Waiting for player to close.")
        Await.result(f, 3.seconds)
      } catch {
        case e: Exception =>
          log.warn("Error when closing player!", e)
      }
    }

    completionNotifier.shutdownComplete()
  }

  /**
    * Initializes the radio player. This has to be done in a synchronized block
    * because playback context factories can arrive at any time.
    *
    * @param p the newly created player object
    */
  private def initPlayer(p: RadioPlayer): Unit = {
    this.synchronized {
      pendingPlaybackContextFactories foreach p.addPlaybackContextFactory
      player = Some(p)
    }
    pendingPlaybackContextFactories = Nil
  }

  /**
    * Creates a sink for listening for radio player events. All received events
    * are wrapped in [[RadioPlayerEvent]] objects and published on the message
    * bus.
    *
    * @param player the radio player
    * @return the event listener sink
    */
  private def createPlayerListenerSink(player: RadioPlayer): Sink[PlayerEvent, _] = {
    val messageBus = clientApplicationContext.messageBus
    Sink.foreach[PlayerEvent] { e =>
      messageBus.publish(RadioPlayerEvent(e, player))
    }
  }
}
