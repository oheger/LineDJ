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

package de.oliver_heger.linedj.archive.server.model

import de.oliver_heger.linedj.shared.archive.metadata.Checksums

/**
  * A module that defines the API model and JSON formats for the archive server
  * API.
  */
object ArchiveModel:
  /**
    * An enumeration defining the supported ways to order the songs on a
    * medium.
    *
    * This can be used to give hints to player applications in which order the
    * songs on a medium should be played, unless the user has explicitly
    * defined a playlist.
    */
  enum OrderMode:
    /**
      * The order mode ''Album''. This means that songs are grouped by the
      * albums they belong to. The groups are ordered by the albums' inception
      * years, and then by the albums' titles. The songs on a single album are
      * ordered by the track numbers. So, this ordering can be used to bring
      * songs in a chronological order.
      */
    case Album

    /**
      * The order mode ''Artist''. This means that songs are grouped by their
      * artists, the groups are sorted alphabetically by the artist name. The
      * songs within a group are sorted via [[Album]] ordering.
      */
    case Artist

    /**
      * The order mode ''Medium''. This means that songs are ordered by their
      * URLs. It does not depend on the metadata for songs, but is derived from
      * the structure of the medium.
      */
    case Medium

    /**
      * The order mode ''random albums''. This means that songs are grouped by
      * the albums they belong to and ordered by their track number. But the
      * albums are played in random order.
      */
    case RandomAlbums

    /**
      * The order mode ''random artists''. This means that songs are grouped by
      * the artist they belong to and played in the order as defined by
      * [[Artist]]. The artists themselves are played in random order.
      */
    case RandomArtists

    /**
      * The order mode ''random songs''. This means a random order of all songs
      * contained on the medium.
      */
    case RandomSongs

  /**
    * A data class with overview information about a medium.
    *
    * This class is used to return information about multiple media. Therefore,
    * it contains only limited properties to restrict the size of responses.
    *
    * @param id    the ID of the medium which is derived from its checksum
    * @param title the title of the medium
    */
  case class MediumOverview(id: Checksums.MediumChecksum,
                            title: String)

  /**
    * A data class to fully describe a medium.
    *
    * An instance contains all the information available for a medium. This
    * class is used when a user requests the details about a medium.
    *
    * @param overview    the object with the overview properties
    * @param description a description for this medium
    * @param orderMode   the default order mode for playing the songs on the
    *                    medium if defined
    */
  case class MediumDetails(overview: MediumOverview,
                           description: String,
                           orderMode: Option[OrderMode]):
    export overview.*
