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

import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.mediaifc.ext.MetadataCache.MediumContent
import de.oliver_heger.linedj.platform.mediaifc.ext.{AvailableMediaExtension, MetadataCache}
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediaFileID, MediumID, MediumInfo}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Success

object MetadataServiceImplSpec:
  /**
    * Generates a test medium ID based on the given index.
    *
    * @param idx the index
    * @return the test medium ID with this index
    */
  def mediumID(idx: Int): MediumID =
    MediumID("mediumURI" + idx, Some(s"testMedium$idx/playlist.settings"))

  /**
    * Generates a test medium info object based on the given index.
    *
    * @param idx the index
    * @return the test medium info with this index
    */
  def mediumInfo(idx: Int): MediumInfo =
    MediumInfo(name = "Medium" + idx, description = "Test medium " + idx,
      mediumID = mediumID(idx), checksum = idx.toString, orderMode = null)

  /**
    * Generates a mapping from a medium ID to a medium info based on the given
    * index.
    *
    * @param idx the index
    * @return the tuple with the medium ID and the medium info of this index
    */
  def mediumInfoMapping(idx: Int): (MediumID, MediumInfo) =
    val info = mediumInfo(idx)
    info.mediumID -> info

  /**
    * Generates test metadata for a song of a medium.
    *
    * @param mid the medium ID
    * @param idx the index of the song
    * @return the test metadata for the given parameters
    */
  def metaData(mid: MediumID, idx: Int): MediaMetadata =
    MediaMetadata(title = Some(s"Title ${mid.mediumURI} - song$idx"))

  /**
    * Generates a map with metadata for the given test medium ID in the range
    * specified.
    *
    * @param mid  the medium ID
    * @param from the start index of metadata
    * @param to   the end index of metadata (including)
    * @return the map with test metadata
    */
  def metaDataMap(mid: MediumID, from: Int, to: Int): Map[MediaFileID, MediaMetadata] =
    (from to to).map { idx =>
      val fileID = MediaFileID(mid, s"songUri$idx")
      fileID -> metaData(mid, idx)
      }.toMap

/**
  * Test class for ''MetaDataServiceImpl''.
  */
class MetadataServiceImplSpec extends AnyFlatSpec with Matchers:

  import MetadataServiceImplSpec._

  "MetadataServiceImpl" should "fetch available media" in:
    val Media = AvailableMedia(List(mediumInfoMapping(1)))
    val bus = new MessageBusTestImpl
    val funcMedia = MetadataServiceImpl.fetchMedia()

    val futMedia = funcMedia(bus)
    val reg = bus.expectMessageType[AvailableMediaExtension.AvailableMediaRegistration]
    reg.id should not be null
    reg.callback(Media)
    val unRegistration = bus.expectMessageType[AvailableMediaExtension.AvailableMediaUnregistration]
    unRegistration.id should be(reg.id)
    futMedia.isCompleted shouldBe true
    futMedia.value should be(Some(Success(Media)))

  it should "handle multiple available media invocations of the callback" in:
    val Media1 = AvailableMedia(List(mediumInfoMapping(1)))
    val Media2 = AvailableMedia(List(mediumInfoMapping(1), mediumInfoMapping(2)))
    val bus = new MessageBusTestImpl
    val funcMedia = MetadataServiceImpl.fetchMedia()
    val futMedia = funcMedia(bus)
    val reg = bus.expectMessageType[AvailableMediaExtension.AvailableMediaRegistration]
    reg.callback(Media1)

    reg.callback(Media2)
    futMedia.value should be(Some(Success(Media1)))

  it should "fetch metadata for a medium" in:
    val Mid = mediumID(1)
    val expMetaData = metaDataMap(Mid, 1, 16)
    val bus = new MessageBusTestImpl
    val funcMeta = MetadataServiceImpl.fetchMetadataOfMedium(Mid)

    val futMeta = funcMeta(bus)
    val reg = bus.expectMessageType[MetadataCache.MetadataRegistration]
    reg.id should not be null
    reg.mediumID should be(Mid)
    reg.callback(MediumContent(metaDataMap(Mid, 1, 8), complete = false))
    futMeta.isCompleted shouldBe false
    reg.callback(MediumContent(metaDataMap(Mid, 9, 16), complete = true))
    futMeta.value should be(Some(Success(expMetaData)))
