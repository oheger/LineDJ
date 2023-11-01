/*
 * Copyright 2015-2023 The Developers Team.
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

import de.oliver_heger.linedj.platform.app.support.{ActorClientSupport, ActorManagementComponent}
import de.oliver_heger.linedj.platform.app.{ApplicationAsyncStartup, ClientApplication}
import de.oliver_heger.linedj.platform.audio.actors.PlayerManagerActor
import de.oliver_heger.linedj.platform.audio.actors.PlayerManagerActor.PlayerManagementCommand
import de.oliver_heger.linedj.platform.bus.Identifiable
import de.oliver_heger.linedj.player.engine.PlaybackContextFactory
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import net.sf.jguiraffe.gui.app.ApplicationContext
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import org.apache.pekko.actor.typed.{ActorRef, Scheduler}
import org.apache.pekko.util.Timeout
import org.osgi.service.component.ComponentContext

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise, TimeoutException}

/**
  * The ''Application'' class for the radio player application.
  *
  * This class is a JGUIraffe application and a declarative services
  * component. It declares a dynamic reference to services of type
  * ''PlaybackContextFactory''. Such services are needed by the audio player
  * engine for creating correct audio streams.
  *
  * On startup, an [[RadioPlayerManagerActor]] is created that is responsible
  * for the asynchronous creation and initialization of the radio player.
  * [[PlaybackContextFactory]] services are managed by this actor as well. When
  * the player has been created (using the internal [[RadioPlayerFactory]]
  * helper class, it is passed to the [[RadioController]] via a message on the
  * message bus.
  *
  * @param playerFactory the factory for creating a radio player
  */
class RadioPlayerApplication(private[radio] val playerFactory: RadioPlayerFactory) extends
  ClientApplication("radioplayer") with ApplicationAsyncStartup with ActorManagementComponent
  with ActorClientSupport with Identifiable:
  def this() = this(new RadioPlayerFactory)

  /**
    * A promise that gets completed when the UI of the application is active.
    * The promise is taken into account by the function that creates the radio
    * player. This is rather a hack to prevent the function from completing
    * before the UI; this would cause the message with the initialized radio
    * player to get lost. A cleaner solution would be to have an analogous
    * setup as for the audio player with a controller registered on the message
    * bus and an OSGi service dependency.
    */
  private val promiseUI = Promise[Unit]()

  /**
    * A list for storing playback context factories that arrive before the
    * radio player was created.
    */
  private var pendingPlaybackContextFactories = List.empty[PlaybackContextFactory]

  /** The reference to the actor managing the client. */
  private var optManagerActor: Option[ActorRef[PlayerManagementCommand]] = None

  /**
    * Adds a ''PlaybackContextFactory'' service to this application. If the
    * management actor has already been created, the factory is passed to this
    * actor. Otherwise, it is stored until the creation of this actor. Note
    * that no special synchronization is necessary; all access to this field
    * happens in the OSGi management thread.
    *
    * @param factory the factory service to be added
    */
  def addPlaylistContextFactory(factory: PlaybackContextFactory): Unit =
    optManagerActor match
      case Some(actor) => actor ! PlayerManagerActor.AddPlaybackContextFactories(List(factory))
      case None => pendingPlaybackContextFactories = factory :: pendingPlaybackContextFactories

  /**
    * Removes a ''PlaybackContextFactory'' service from this application. This
    * operation is delegated to the management actor if it is already
    * available. This method is called by the declarative services runtime.
    *
    * @param factory the factory service to be removed
    */
  def removePlaylistContextFactory(factory: PlaybackContextFactory): Unit =
    optManagerActor match
      case Some(actor) => actor ! PlayerManagerActor.RemovePlaybackContextFactories(List(factory))
      case None => pendingPlaybackContextFactories = pendingPlaybackContextFactories filterNot (_ == factory)

  /**
    * @inheritdoc This implementation stores the user configuration as a bean,
    *             so that it can be accessed by  the controller. It also
    *             completes the UI promise to indicate that the UI is now
    *             available.
    */
  override def initGUI(appCtx: ApplicationContext): Unit =
    addBeanDuringApplicationStartup("radioApp_config", getUserConfiguration)

    super.initGUI(appCtx)

    promiseUI.success(())

  /**
    * @inheritdoc This implementation performs some registrations.
    */
  override def activate(compContext: ComponentContext): Unit =
    super.activate(compContext)

    val managerBehavior = RadioPlayerManagerActor(clientApplicationContext.messageBus)(createPlayer)
    val managerActor = clientApplicationContext.actorFactory.createActor(managerBehavior, "radioPlayerManagerActor")
    managerActor ! PlayerManagerActor.AddPlaybackContextFactories(pendingPlaybackContextFactories)
    optManagerActor = Some(managerActor)

  /**
    * @inheritdoc This implementation closes the player.
    */
  override def deactivate(componentContext: ComponentContext): Unit =
    closePlayer()
    super.deactivate(componentContext)

  /**
    * Closes the manager actor and waits for its termination.
    */
  private[radio] def closePlayer(): Unit =
    val shutdownTimeout = 3.seconds
    optManagerActor.foreach { actor =>
      implicit val timeout: Timeout = Timeout(shutdownTimeout)
      implicit val scheduler: Scheduler = clientApplicationContext.actorSystem.toTyped.scheduler
      val futureAck = actor.ask[PlayerManagerActor.CloseAck] { ref =>
        PlayerManagerActor.Close(ref, timeout)
      }

      try
        Await.ready(futureAck, shutdownTimeout)
      catch
        case _: TimeoutException =>
          log.warn("Timeout when shutting down audio player!")
    }

  /**
    * Creates the [[RadioPlayer]] asynchronously. Note that the resulting
    * ''Future'' will not be completed before the UI has been initialized.
    *
    * @return a ''Future'' with the [[RadioPlayer]].
    */
  private def createPlayer(): Future[RadioPlayer] =
    for
      player <- playerFactory.createRadioPlayer(this)
      _ <- promiseUI.future
    yield player
