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

package de.oliver_heger.linedj.platform.mediaifc.service

import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.bus.ConsumerSupport.ConsumerFunction
import de.oliver_heger.linedj.platform.mediaifc.MetadataService
import de.oliver_heger.linedj.platform.mediaifc.ext.MetadataCache.MediumContent
import de.oliver_heger.linedj.platform.mediaifc.ext.{AvailableMediaExtension, MetadataCache}
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediaFileID, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import scalaz.Kleisli

import scala.concurrent.Promise

/**
  * Implementation object of the metadata service.
  */
object MetadataServiceImpl extends MetadataService[AvailableMedia, Map[MediaFileID, MediaMetadata]]:
  override def fetchMedia(): MetadataResult[AvailableMedia] = Kleisli { messageBus =>
    val promise = Promise[AvailableMedia]()
    val compID = ComponentID()
    val callback: ConsumerFunction[AvailableMedia] = media => {
      messageBus publish AvailableMediaExtension.AvailableMediaUnregistration(compID)
      promise.trySuccess(media)
    }
    val reg = AvailableMediaExtension.AvailableMediaRegistration(compID, callback)
    messageBus publish reg
    promise.future
  }

  override def fetchMetadataOfMedium(mediumID: MediumID): MetadataResult[Map[MediaFileID, MediaMetadata]] =
    Kleisli { messageBus =>
      val promise = Promise[Map[MediaFileID, MediaMetadata]]()
      val chunkData = collection.mutable.Map.empty[MediaFileID, MediaMetadata]
      val callback: ConsumerFunction[MediumContent] = chunk => {
        chunkData ++= chunk.data
        if chunk.complete then
          promise.success(chunkData.toMap)
      }
      val reg = MetadataCache.MetadataRegistration(mediumID, ComponentID(), callback)
      messageBus publish reg
      promise.future
    }
