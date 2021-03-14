/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.archive.group

import akka.actor.{Actor, ActorRef, Props}
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.shared.archive.media.{MediaScanCompleted, ScanAllMedia, StartMediaScan}
import de.oliver_heger.linedj.utils.ChildActorFactory

object ArchiveGroupActor {
  /**
    * Returns a ''Props'' object for creating an instance of this actor class.
    *
    * @param mediaUnionActor    the media actor of the union archive
    * @param metaDataUnionActor the meta data actor of the union archive
    * @param archiveConfigs     the configurations of the archives in the group
    * @return a ''Props'' object to create a new instance
    */
  def apply(mediaUnionActor: ActorRef, metaDataUnionActor: ActorRef,
            archiveConfigs: Seq[MediaArchiveConfig]): Props =
    Props(classOf[ArchiveGroupActorImpl], mediaUnionActor, metaDataUnionActor, archiveConfigs,
      GroupScanStateServiceImpl)

  private class ArchiveGroupActorImpl(mediaUnionActor: ActorRef, metaDataUnionActor: ActorRef,
                                      archiveConfigs: Seq[MediaArchiveConfig],
                                      private val scanStateService: GroupScanStateService)
    extends ArchiveGroupActor(mediaUnionActor, metaDataUnionActor, archiveConfigs, scanStateService)
      with ArchiveActorFactory with ChildActorFactory

}

/**
  * An actor that manages media archives that belong to a group.
  *
  * An instance is configured with the configurations of the archives it has to
  * manage. For each configuration, an archive is started (i.e. the
  * corresponding actors are created, and an initial scan operation is
  * triggered).
  *
  * In addition, it makes sure that scan operations of the managed archives are
  * coordinated: Only a single scan operation can be active at a given time for
  * all of the archives that belong to the group.
  *
  * @param mediaUnionActor    the media actor of the union archive
  * @param metaDataUnionActor the meta data actor of the union archive
  * @param archiveConfigs     the configurations of the archives in the group
  * @param scanStateService   the service to manage the scan state
  */
class ArchiveGroupActor(mediaUnionActor: ActorRef, metaDataUnionActor: ActorRef,
                        archiveConfigs: Seq[MediaArchiveConfig],
                        private val scanStateService: GroupScanStateService) extends Actor {
  this: ArchiveActorFactory =>

  /** The current scan state of the archive group. */
  private var scanState = GroupScanStateServiceImpl.InitialState

  /**
    * @inheritdoc This implementation creates the actors for the archives in
    *             the group and triggers an initial media scan.
    */
  override def preStart(): Unit = {
    super.preStart()

    archiveConfigs map { config =>
      createArchiveActors(mediaUnionActor, metaDataUnionActor, self, config)
    } foreach (_ ! ScanAllMedia)
  }

  override def receive: Receive = {
    case ScanAllMedia =>
      updateState(scanStateService.handleScanRequest(sender()))

    case MediaScanCompleted =>
      updateState(scanStateService.handleScanCompleted())
  }

  /**
    * Updates the internal group scan state based on the passed in update
    * object. Sends new ''StartMediaScan'' messages as necessary.
    *
    * @param update the update object
    */
  private def updateState(update: GroupScanStateServiceImpl.StateUpdate[Option[ActorRef]]): Unit = {
    val (next, target) = update(scanState)
    target foreach (_ ! StartMediaScan)
    scanState = next
  }
}
