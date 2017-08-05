/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.archivecommon.download

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props, Terminated}
import de.oliver_heger.linedj.shared.archive.media.DownloadActorAlive
import de.oliver_heger.linedj.utils.SchedulerSupport

object DownloadMonitoringActor {

  /**
    * A message processed by [[DownloadMonitoringActor]] notifying it about a new
    * download operation.
    *
    * Data about the download operation is stored, so that it can be tracked,
    * and timeouts can be detected.
    *
    * @param downloadActor the download actor
    * @param client        the client actor
    */
  case class DownloadOperationStarted(downloadActor: ActorRef, client: ActorRef)

  /**
    * An internal message the download manager actor sends to itself to check
    * for download actors that timed out.
    */
  private[download] case object CheckDownloadTimeout

  private class DownloadMonitoringActorImpl(config: DownloadConfig)
    extends DownloadMonitoringActor(config) with SchedulerSupport

  /**
    * Returns the current system time.
    *
    * @return the current time in millis
    */
  private def now(): Long = System.currentTimeMillis()

  /**
    * Returns a ''Props'' object for creating a new instance of the
    * [[DownloadMonitoringActor]] class.
    *
    * @param config the configuration for download operations
    * @return ''Props'' to create a new actor instance
    */
  def apply(config: DownloadConfig): Props =
    Props(classOf[DownloadMonitoringActorImpl], config)
}

/**
  * An actor class which monitors download operations.
  *
  * This class is responsible for keeping track of all download actors
  * currently in progress for a media archive. Via a download actor, a client
  * gets access to a media file managed by an archive. The client can read
  * the media file chunk-wise and has to stop the download actor when it is
  * done.
  *
  * This protocol has a risk of leaking download actors, e.g. if a client
  * crashes before it can stop the actor and simply forgets to terminate the
  * download operation correctly. Therefore, it has to be checked in regular
  * intervals whether a download actor is still in use. This actor uses a
  * scheduler to handle such checks. It has to be notified whenever a new
  * download operation is started. It then stores the download actor affected
  * and checks periodically whether it is still in use.
  *
  * @param config       the configuration for download operations
  * @param downloadData a helper object for managing download actors
  */
class DownloadMonitoringActor(config: DownloadConfig,
                              private[download] val downloadData: DownloadActorData)
  extends Actor with ActorLogging {
  me: SchedulerSupport =>

  import DownloadMonitoringActor._

  /** Cancelable for the scheduler invocation for periodic timeout checks. */
  private var timeoutCheckCancelable: Cancellable = _

  /**
    * Creates a new instance of ''DownloadManagerActor'' with the specified
    * configuration. This constructor creates default dependencies.
    *
    * @param config the configuration for download operations
    * @return the new actor instance
    */
  def this(config: DownloadConfig) = this(config, new DownloadActorData)

  override def preStart(): Unit = {
    timeoutCheckCancelable = scheduleMessage(config.downloadCheckInterval,
      config.downloadCheckInterval, self, CheckDownloadTimeout)
  }

  override def postStop(): Unit = {
    timeoutCheckCancelable.cancel()
    super.postStop()
  }

  override def receive: Receive = {
    case DownloadOperationStarted(downloadActor, client) =>
      if (downloadData.findReadersForClient(client).isEmpty) {
        context watch client
      }
      downloadData.add(downloadActor, client, now())
      context watch downloadActor
      log.info("Registered download actor.")

    case CheckDownloadTimeout =>
      checkForDownloadActorTimeout()

    case DownloadActorAlive(reader, _) =>
      downloadData.updateTimestamp(reader, now())

    case t: Terminated =>
      handleActorTermination(t.actor)
  }

  /**
    * Handles an actor terminated message. We have to determine which type of
    * actor is affected by this message. If it is a reader actor, then a
    * download operation is finished, and some cleanup has to be done. It can
    * also be the client actor of a read operation; then all reader actors
    * related to this client can be canceled.
    *
    * @param actor the affected actor
    */
  private def handleActorTermination(actor: ActorRef): Unit = {
    if (downloadData hasActor actor) {
      handleDownloadActorTermination(actor)
    } else {
      val downloadActors = downloadData.findReadersForClient(actor)
      downloadActors foreach context.stop
    }
  }

  /**
    * Handles the termination of a reader actor. We can stop watching
    * the client actor if there are no more pending read operations on behalf
    * of it.
    *
    * @param actor the terminated actor
    */
  private def handleDownloadActorTermination(actor: ActorRef): Unit = {
    log.info("Removing terminated download actor from mapping.")
    val optClient = downloadData remove actor
    optClient foreach { c =>
      if (downloadData.findReadersForClient(c).isEmpty) {
        context unwatch c
      }
    }
  }

  /**
    * Checks all currently active download actors for timeouts. This method is
    * called periodically. It checks whether there are actors which have not
    * been updated during a configurable interval. This typically indicates a
    * crash of the corresponding client.
    */
  private def checkForDownloadActorTimeout(): Unit = {
    downloadData.findTimeouts(now(), config.downloadTimeout) foreach
      stopDownloadActor
  }

  /**
    * Stops a download actor when the timeout was reached.
    *
    * @param actor the actor to be stopped
    */
  private def stopDownloadActor(actor: ActorRef): Unit = {
    context stop actor
    log.warning("Download actor {} stopped because of timeout!", actor.path)
  }
}
