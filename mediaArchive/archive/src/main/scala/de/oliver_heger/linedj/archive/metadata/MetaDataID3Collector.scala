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

package de.oliver_heger.linedj.archive.metadata

import de.oliver_heger.linedj.archive.mp3.ID3TagProvider
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData

object MetaDataID3Collector {
  private def createCombinedProvider(providers: List[ProviderData]): ID3TagProvider = {
    val orderedProviders = providers sortWith(_.id3Version > _.id3Version) map(_.provider)
    new CombinedID3TagProvider(orderedProviders)
  }

  /**
   * A data class for storing information about ID3 providers together with
   * their ID3 version.
   * @param id3Version the version
   * @param provider the provider
   */
  private case class ProviderData(id3Version: Int, provider: ID3TagProvider)

  /**
   * An internally used implementation of ''ID3TagProvider'' that is based on a
   * list of sub providers. For each property queried from this object the list
   * is traversed until a defined value is found or the end is reached. The
   * class assumes that the list is ordered based on some criteria.
   * @param providers the list with the managed providers
   */
  private class CombinedID3TagProvider(providers: List[ID3TagProvider]) extends ID3TagProvider {
    override def title: Option[String] = property(_.title)

    override def inceptionYearString: Option[String] = property(_.inceptionYearString)

    override def album: Option[String] = property(_.album)

    override def trackNoString: Option[String] = property(_.trackNoString)

    override def artist: Option[String] = property(_.artist)

    /**
     * Retrieves the value of a property selected by the passed in property
     * function. This method searches the list of providers whether a value for
     * the property can be found that is not ''None''.
     * @param p a function selecting a property
     * @return the value of the property selected by the function
     */
    private def property(p: ID3TagProvider => Option[String]): Option[String] = {
      val definingProvider = providers find (p(_).isDefined)
      definingProvider flatMap p
    }
  }
}

/**
 * An internally used helper class for collecting meta data extracted from ID3
 * tags.
 *
 * A single MP3 file can contain multiple ID3 frames of different versions.
 * This class collects them and tries to provide all the data required for
 * filling a [[MediaMetaData]] structure.
 *
 * To an instance of ''MetaDataID3Collector'' multiple
 * [[de.oliver_heger.linedj.archive.mp3.ID3TagProvider]] objects can be added, together
 * with the ID3 version they stem from. From this information a combined
 * ''ID3TagProvider'' is created providing access to the accumulated data. If
 * there are multiple values for meta data from different ID3 frames, frames
 * with a higher version take precedence. (Well, this is an arbitrary
 * decision, but some algorithm has to be used to resolve redundancies.)
 */
private class MetaDataID3Collector {
  import MetaDataID3Collector._

  /** A list that stores data about the providers that have been added.*/
  private var providers = List.empty[ProviderData]

  /**
   * Adds an ''ID3TagProvider'' to this object. The values returned by this
   * provider will be taken into account when creating the final combined
   * provider. The passed in version is used to prioritize data if there are
   * clashes.
   * @param id3Version the version of the ID3 frame this data comes from
   * @param provider the provider to be added
   * @return this object
   */
  def addProvider(id3Version: Int, provider: ID3TagProvider): MetaDataID3Collector = {
    providers = ProviderData(provider = provider, id3Version = id3Version) :: providers
    this
  }

  /**
   * Creates an ''ID3TagProvider'' object that collects the data of all
   * providers that have been added so far to this object.
   * @return an ''ID3TagProvider'' with accumulated data
   */
  def createCombinedID3TagProvider(): ID3TagProvider = {
    createCombinedProvider(providers)
  }
}
