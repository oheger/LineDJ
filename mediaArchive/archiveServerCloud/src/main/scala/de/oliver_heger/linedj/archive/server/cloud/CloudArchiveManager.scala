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

import de.oliver_heger.linedj.archive.cloud.auth.Credentials
import de.oliver_heger.linedj.archive.cloud.{CloudArchiveConfig, CloudFileDownloader, CloudFileDownloaderFactory, DefaultCloudFileDownloaderFactory}
import de.oliver_heger.linedj.archive.server.model.ArchiveCommands
import de.oliver_heger.linedj.shared.actors.ActorFactory
import de.oliver_heger.linedj.shared.actors.ActorFactory.executionContext
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.{ActorSystem, typed}
import org.apache.pekko.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.util.Timeout

import java.nio.file.Path
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

/**
  * A module providing a component to manage the startup of cloud archives.
  *
  * When creating an instance, the [[CloudArchiveServerConfig]] has to be
  * provided. The instance then uses a [[CloudFileDownloaderFactory]] to
  * schedule the creation of downloader objects for the managed archives as
  * soon as the required credentials are available. With these downloader
  * objects, it can download the metadata from the cloud archives and populate
  * a content actor.
  *
  * The archive manager keeps track on the state of the managed archives, i.e.,
  * whether they have been started, are still waiting for credentials, or their
  * startup failed. In the latter case, it resets the credentials for affected
  * archives, so that another attempt can be made to start them. (This is
  * especially useful if the provided credentials were wrong.)
  */
object CloudArchiveManager:
  /** The name of the actor that stores the state of cloud archives. */
  private val StateActorName = "archiveStateActor"

  /**
    * A factory trait for creating new instances of [[CloudArchiveManager]].
    */
  trait Factory:
    /**
      * Creates a new instance of [[CloudArchiveManager]] that uses the
      * provided objects to start the managed cloud archives.
      *
      * @param actorFactory      the actor factory
      * @param contentActor      the actor managing the archive content
      * @param config            the configuration of this server application;
      *                          this includes the configuration of the
      *                          managed archives
      * @param downloaderFactory the factory to create a downloader for an
      *                          archive
      * @param credentialSetter  the object to set credentials
      * @param contentLoader     the object to load metadata from cloud
      *                          archives
      * @param cacheFactory      a factory to create local caches for the
      *                          metadata of cloud archives
      *
      * @param system            the actor system
      * @return                  the newly created archive manager
      */
    def apply(actorFactory: ActorFactory,
              contentActor: ActorRef[ArchiveCommands.UpdateArchiveContentCommand],
              config: CloudArchiveServerConfig,
              downloaderFactory: CloudFileDownloaderFactory,
              credentialSetter: Credentials.CredentialSetter,
              contentLoader: CloudArchiveContentLoader,
              cacheFactory: CloudArchiveCache.Factory = CloudArchiveCache.newInstance)
             (using system: classic.ActorSystem): CloudArchiveManager

  /**
    * An enumeration class to represent the different states a managed cloud
    * archive can be in.
    */
  enum CloudArchiveState:
    /**
      * The archive could not be accessed since it is waiting for credentials
      * to be provided.
      */
    case Waiting

    /**
      * The data from the archive has been loaded. It can be accessed, media
      * files can be downloaded using the contained [[ContentDownloader]].
      *
      * @param downloader the [[ContentDownloader]] for this archive
      */
    case Loaded(downloader: ContentDownloader)

    /**
      * Loading of the archive has failed. The state stores the latest error
      * that occurred and the number of attempts that have been made to load
      * the archive's content.
      *
      * @param exception the last error that occurred when loading the
      *                  archive
      *
      * @param attempts  the number of attempts that were made to
      *                  load the archive
      */
    case Failure(exception: Throwable, attempts: Int)

  /**
    * A data class that holds the state of the managed archives. The state
    * consists of a map whose keys are the names of archives and whose values
    * are the corresponding states.
    *
    * @param state the map with the states of the managed archives
    */
  case class ArchivesState(state: Map[String, CloudArchiveState])

  /**
    * An enumeration class representing the commands supported by the state
    * manager actor.
    */
  private enum StateActorCommand:
    /**
      * Updates the state for a specific archive. This command is sent when an
      * archive has been loaded either successfully of unsuccessfully.
      *
      * @param archiveName the name of the affected archive
      * @param state       the updated state
      */
    case UpdateArchiveState(archiveName: String, state: CloudArchiveState)

    /**
      * Command to query the current state of the managed archives.
      *
      * @param replyTo the actor to send the response to
      */
    case QueryArchiveState(replyTo: ActorRef[ArchivesState])

    /**
      * Command to stop the state manager actor.
      */
    case Stop

  /**
    * A default [[Factory]] for creating new instances of
    * [[CloudArchiveManager]].
    */
  final val newInstance: Factory = new Factory:
    override def apply(actorFactory: ActorFactory,
                       contentActor: ActorRef[ArchiveCommands.UpdateArchiveContentCommand],
                       config: CloudArchiveServerConfig,
                       downloaderFactory: CloudFileDownloaderFactory,
                       credentialSetter: Credentials.CredentialSetter,
                       contentLoader: CloudArchiveContentLoader,
                       cacheFactory: CloudArchiveCache.Factory)
                      (using system: ActorSystem): CloudArchiveManager =
      given typed.ActorSystem[_] = system.toTyped

      // A short timeout to query the state actor.
      given stateActorTimeout: Timeout = Timeout(3.seconds)

      val initialState = config.archives.map: archive =>
        archive.archiveName -> CloudArchiveState.Waiting
      .toMap
      val stateActor = actorFactory.createTypedActor(
        handleStateActorCommand(ArchivesState(initialState)),
        StateActorName,
        optStopCommand = Some(StateActorCommand.Stop)
      )

      config.archives.foreach: archive =>
        prepareLoadArchive(
          stateActor,
          contentActor,
          config.cacheDirectory,
          archive,
          downloaderFactory,
          credentialSetter,
          contentLoader,
          cacheFactory
        )

      new CloudArchiveManager:
        override def archivesState: Future[ArchivesState] =
          stateActor.ask[ArchivesState](ref => StateActorCommand.QueryArchiveState(ref))

  /**
    * Prepares the load operation for one of the managed archives by triggering
    * the creation of the downloader for this archive and performing the
    * necessary steps once the downloader becomes available. When this is the
    * case, the state for this archive is updated.
    *
    * @param stateActor        the actor managing the archive state
    * @param contentActor      the content actor
    * @param cacheDirectory    the root directory for local caches
    * @param archiveConfig     the configuration for the archive
    * @param downloaderFactory the factory for creating downloader objects
    * @param credentialSetter  the object to set credentials
    * @param contentLoader     the object to load the archives content
    * @param cacheFactory      the factory to create local caches
    * @param system            the actor system
    */
  private def prepareLoadArchive(stateActor: ActorRef[StateActorCommand],
                                 contentActor: ActorRef[ArchiveCommands.UpdateArchiveContentCommand],
                                 cacheDirectory: Path,
                                 archiveConfig: ArchiveConfig,
                                 downloaderFactory: CloudFileDownloaderFactory,
                                 credentialSetter: Credentials.CredentialSetter,
                                 contentLoader: CloudArchiveContentLoader,
                                 cacheFactory: CloudArchiveCache.Factory)
                                (using system: ActorSystem): Unit =
    val cloudArchiveConfig: CloudArchiveConfig = archiveConfig
    (for
      downloader <- downloaderFactory.createDownloader(cloudArchiveConfig)
      contentDownloader <- createContentDownloader(cloudArchiveConfig, downloader)
      cache = cacheFactory(cacheDirectory, archiveConfig.archiveName)
      _ <- contentLoader.loadContent(
        contentDownloader,
        cache,
        archiveConfig.archiveName,
        contentActor,
        cloudArchiveConfig.parallelism,
        cloudArchiveConfig.maxContentSize
      )
    yield contentDownloader).onComplete:
      case Success(contentDownloader) =>
        val state = CloudArchiveState.Loaded(contentDownloader)
        stateActor ! StateActorCommand.UpdateArchiveState(archiveConfig.archiveName, state)
      case Failure(exception) =>
        clearCredentials(credentialSetter, cloudArchiveConfig)
        val archiveState = CloudArchiveState.Failure(exception, 1)
        stateActor ! StateActorCommand.UpdateArchiveState(archiveConfig.archiveName, archiveState)
        prepareLoadArchive(
          stateActor,
          contentActor,
          cacheDirectory,
          archiveConfig,
          downloaderFactory,
          credentialSetter,
          contentLoader,
          cacheFactory
        )

  /**
    * Creates a [[ContentDownloader]] from the passed in parameters and returns
    * it as a [[Future]], so that the value can be combined with other future
    * results.
    *
    * @param archiveConfig the configuration for the affected archive
    * @param downloader    the [[CloudFileDownloader]] to be used
    * @return a [[Future]] with the new [[ContentDownloader]]
    */
  private def createContentDownloader(archiveConfig: CloudArchiveConfig,
                                      downloader: CloudFileDownloader): Future[ContentDownloader] =
    Future.successful(new ContentDownloader(archiveConfig, downloader))

  /**
    * The command handler function of the state actor. This actor stores the
    * current state of the managed archives.
    *
    * @param states the object with the current archive state
    * @return the [[Behavior]] of the state actor
    */
  private def handleStateActorCommand(states: ArchivesState): Behavior[StateActorCommand] =
    Behaviors.receive:
      case (_, StateActorCommand.QueryArchiveState(replyTo)) =>
        replyTo ! states
        Behaviors.same

      case (ctx, StateActorCommand.UpdateArchiveState(archiveName, state)) =>
        ctx.log.info("Setting state for archive '{}' to {}.", archiveName, state)
        val updatedState = state match
          case CloudArchiveState.Failure(exception, _) =>
            CloudArchiveState.Failure(exception, failedLoadAttempts(states, archiveName) + 1)
          case s => s
        val nextStates = states.state + (archiveName -> updatedState)
        handleStateActorCommand(ArchivesState(nextStates))

      case (ctx, StateActorCommand.Stop) =>
        ctx.log.info("Stopping state manager actor.")
        Behaviors.stopped

  /**
    * Fetches the number of failed attempts to load an archive from the given
    * object with archive states.
    *
    * @param states      the object with archive states
    * @param archiveName the name of the affected archive
    * @return the number of failed attempts to load this archive
    */
  private def failedLoadAttempts(states: ArchivesState, archiveName: String): Int =
    states.state(archiveName) match
      case CloudArchiveState.Failure(_, attempts) => attempts
      case _ => 0

  /**
    * Clears the credentials used by the archive with the given configuration.
    * This function is called when loading of an archive failed. Then its
    * credentials need to be cleared, so that another attempt can be made
    * later.
    *
    * @param credentialSetter the object to set credentials
    * @param archiveConfig    the config of the affected archive
    */
  private def clearCredentials(credentialSetter: Credentials.CredentialSetter,
                               archiveConfig: CloudArchiveConfig): Unit =
    DefaultCloudFileDownloaderFactory.credentialKeys(archiveConfig).foreach(credentialSetter.clearCredential)
end CloudArchiveManager

/**
  * A trait defining the API of the cloud archive manager. The trait allows
  * querying the state of the managed archives.
  */
trait CloudArchiveManager:

  import CloudArchiveManager.*

  /**
    * Returns a [[Future]] with the current states of the managed cloud 
    * archives.
    *
    * @return a [[Future]] with the states of the managed archives
    */
  def archivesState: Future[ArchivesState]
