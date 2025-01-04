/*
 * Copyright 2015-2025 The Developers Team.
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

import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.media.{MediaManagerActor, PathUriConverter}
import de.oliver_heger.linedj.archive.metadata.MetadataManagerActor
import de.oliver_heger.linedj.archive.metadata.persistence.PersistentMetadataManagerActor
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.pekko.actor.ActorRef

/**
  * A trait that allows creating all the actors of a media archive.
  *
  * Via the single factory method the single actors, a media archive consists
  * of are created: the media manager, the metadata manager, and the manager
  * for persistent metadata.
  */
trait ArchiveActorFactory:
  this: ChildActorFactory =>

  /**
    * Creates all the actors required for a media archive and returns a
    * reference to the media manager actor. (This is the entry point for most
    * interactions with this archive.)
    *
    * @param mediaUnionActor    the media actor of the union archive
    * @param metadataUnionActor the metadata actor of the union archive
    * @param groupManager       the group manager actor
    * @param archiveConfig      the config of the new archive
    * @return the new media manager actor
    */
  def createArchiveActors(mediaUnionActor: ActorRef, metadataUnionActor: ActorRef, groupManager: ActorRef,
                          archiveConfig: MediaArchiveConfig): ActorRef =
    val converter = new PathUriConverter(archiveConfig.rootPath)
    val persistentMetaDataManager = createChildActor(
      PersistentMetadataManagerActor(archiveConfig, metadataUnionActor, converter))
    val metaDataManager = createChildActor(MetadataManagerActor(archiveConfig,
      persistentMetaDataManager, metadataUnionActor, converter))
    createChildActor(MediaManagerActor(archiveConfig, metaDataManager, mediaUnionActor, groupManager, converter))
