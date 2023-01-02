/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.browser.media

import de.oliver_heger.linedj.platform.audio.model.SongData
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import org.apache.commons.configuration.HierarchicalConfiguration

import scala.collection.{SortedMap, SortedSet}

private object MediumTreeModel {
  /** Constant for the property delimiter. */
  private val Delimiter = "|"

  /**
   * An updater to be used when constructing an initialized model. This updater
   * will not be called actually, but it ensures that no other updaters are
   * created (because ResortUpdaters do not support concatenation).
   */
  private val DummyUpdater = ResortUpdater(Nil)

  /** An empty model object. */
  val empty = new MediumTreeModel(SortedMap.empty, Set.empty)

  /**
   * Creates a ''MediumTreeModel'' that is already initialized with the passed
   * in items. The model is constructed by adding all these items. The order
   * does not matter, the model takes care of sorting.
   * @param items the items for the new model
   * @return the initialized model
   */
  def apply(items: Iterable[(AlbumKey, SongData)]): MediumTreeModel = {
    items.foldLeft(empty) { (m, e) =>
      m.add(e._1, e._2, DummyUpdater)._1
    }
  }

  /**
   * Transforms the given album key to a key for a configuration object.
   * @param ak the album key
   * @return the transformed configuration key
   */
  def toConfigKey(ak: AlbumKeyWithYear): String = {
    val year = if (ak.year != Integer.MAX_VALUE) s"(${ak.year}) " else ""
    ak.key.artist + Delimiter + year + ak.key.album
  }

  /**
   * Creates an ''AlbumKeyWithYear'' object from the given data.
   * @param key the album key
   * @param meta the meta data
   * @return the ''AlbumKeyWithYear''
   */
  private def keyWithYear(key: AlbumKey, meta: MediaMetaData): AlbumKeyWithYear =
    AlbumKeyWithYear(key, meta.inceptionYear getOrElse Integer.MAX_VALUE)
}

/**
 * An internally used helper class serving as the model for the tree which
 * displays a medium.
 *
 * The media browser displays the files contained on a medium grouped by artist
 * and album. Because this is a hierarchical structure a tree is used for
 * navigating through the albums of the different artists.
 *
 * In JGUIraffe, a tree is backed by a hierarchical configuration object. This
 * class can populate such a configuration based on the meta data available for
 * the single media files. Note that meta data may not be available
 * immediately, but can arrive in chunks while the backend scans the available
 * media files. Hence, the tree model needs to be updated when new data
 * becomes available.
 *
 * Updates can become a bit tricky. The goal is to change only necessary parts
 * of the underlying configuration so that the UI remains possibly stable. (It
 * will update when new nodes in the configuration are added or existing ones
 * are deleted.) So if possible, new nodes representing the albums of an artist
 * are just added. If necessary - for instance if the sort order has to be
 * taken into account -, the whole branch for an artist has to be deleted and
 * added anew; this is due to the fact that Commons Configuration has no
 * methods for reordering configuration nodes.
 *
 * The resulting tree has the following structure: Below the root node are
 * nodes for artists. Each artist node has child nodes for the produced
 * albums. Albums are sorted by year and name. Node that meta data can only be
 * evaluated if it is available. Media files without meta data are grouped
 * under specific nodes representing unknown artists or albums.
 *
 * @param data the current data of this model
 * @param knownKeys a set with known combinations of artists and albums
 */
private class MediumTreeModel private(data: SortedMap[String, SortedSet[AlbumKeyWithYear]],
                                      knownKeys: Set[AlbumKey]) {

  import MediumTreeModel._

  /**
   * Updates this model for new meta data that becomes available and returns a
   * ''ConfigurationUpdater'' to keep a configuration in sync. Internal state
   * is updated accordingly. The returned ''ConfigurationUpdater'' can be
   * applied on a configuration to keep track with these changes.
   * @param key the key for the album affected by this change
   * @param meta meta data for the new file
   * @param currentUpdater the current updater
   * @return a new instance with updated state and a ''ConfigurationUpdater''
   */
  def add(key: AlbumKey, meta: SongData, currentUpdater: ConfigurationUpdater):
  (MediumTreeModel, ConfigurationUpdater) = {
    if (knownKeys contains key) {
      (this, currentUpdater)
    } else {
      val albums = data.getOrElse(key.artist, SortedSet.empty[AlbumKeyWithYear])
      val albumWithYear = keyWithYear(key, meta.metaData)
      val newAlbums = albums + albumWithYear
      (new MediumTreeModel(data + (key.artist -> newAlbums), knownKeys + key),
        currentUpdater concat createNextUpdater(albumWithYear, newAlbums, currentUpdater))
    }
  }

  /**
   * Returns a sorted set with all albums of the given artist.
   * @param artist the artist
   * @return the sorted set with all albums
   */
  def albumsFor(artist: String): SortedSet[AlbumKeyWithYear] = data(artist)

  /**
   * Returns an ''ConfigurationUpdater'' object which can initialize an empty
   * configuration with the data stored in this model.
   * @return the updater
   */
  def fullUpdater(): ConfigurationUpdater = ResortUpdater(data.keys)

  /**
   * Creates a configuration updater for the current add operation. If the
   * album could be added at the end of the album list, a key can simply be
   * added to the configuration. Otherwise, a full resort is required.
   * @param albumWithYear the enhanced key with album year
   * @param newAlbums the modified set of albums
   * @param currentUpdater the current updater
   * @return the next updater
   */
  private def createNextUpdater(albumWithYear: AlbumKeyWithYear, newAlbums:
  SortedSet[AlbumKeyWithYear], currentUpdater: ConfigurationUpdater): ConfigurationUpdater = {
    if (newAlbums.last == albumWithYear) KeyUpdater(albumWithYear, currentUpdater)
    else ResortUpdater(List(albumWithYear.key.artist))
  }
}

/**
 * Definition of a trait for updating a ''Configuration'' object.
 *
 * An object implementing this trait is returned by [[MediumTreeModel]]. It is
 * used to keep a configuration (representing the model of a tree view) in sync
 * with the underlying data about artists and their albums. A concrete
 * implementation knows how to update a configuration. When this update
 * actually happens lies in the responsibility of calling code.
 *
 * It is also possible to merge multiple updater objects. This allows the
 * execution of multiple operations in a single step. This can also improve
 * performance because some operations may render others unnecessary; those can
 * then be dropped completely.
 */
private sealed trait ConfigurationUpdater {
  /**
   * Concatenates this object with another ''ConfigurationUpdater''. This method
   * allows for tricky optimizations. For instance, an object implementing a
   * complex update operation may decide that other operations are no longer
   * needed and discard them. This default implementation just returns the
   * passed in object which means, that this updater has to be taken into
   * account. It lies in the responsibility of this other updater to invoke
   * this one.
   * @param other the other updater to be merged
   * @return the resulting updater
   */
  def concat(other: => ConfigurationUpdater): ConfigurationUpdater = other

  /**
   * Applies this updater on the specified configuration.
   * @param config the configuration
   * @param model the ''MediumTreeModel''
   * @return the resulting configuration (as a configuration is not immutable,
   *         the same object as passed in is returned)
   */
  def update(config: HierarchicalConfiguration, model: MediumTreeModel): HierarchicalConfiguration
}

/**
 * An updater implementation which does not do anything. This object is
 * returned if no changes on a configuration are required.
 */
private case object NoopUpdater extends ConfigurationUpdater {
  /**
   * @inheritdoc This implementation does nothing
   */
  override def update(config: HierarchicalConfiguration, model: MediumTreeModel):
  HierarchicalConfiguration = config
}

/**
 * An updater implementation which adds the specified key to a configuration.
 * Multiple instances of this class can be chained together in order to add
 * many keys to a configuration.
 *
 * @param key the key to be added
 * @param prev the previous updater in the chain
 */
private case class KeyUpdater(key: AlbumKeyWithYear, prev: ConfigurationUpdater) extends
ConfigurationUpdater {
  /** The key for the configuration. */
  lazy val configKey = MediumTreeModel toConfigKey key

  override def update(config: HierarchicalConfiguration, model: MediumTreeModel):
  HierarchicalConfiguration = {
    val config2 = prev.update(config, model)
    config2.addProperty(configKey, key.key)
    config2
  }
}

/**
 * An updater implementation which adds all current albums of one or many
 * artists to the given configuration. This is needed when albums are received
 * in wrong order. As the Configuration API does not support reordering of
 * keys, the key for the artist has to be removed, and all albums have to be
 * added anew.
 * @param artists the affected artists
 */
private case class ResortUpdater(artists: Iterable[String]) extends ConfigurationUpdater {
  /**
   * @inheritdoc This updater prevents concatenation. It always performs a
   *             full update of the configuration regarding the affected
   *             artists. No further actions are required
   */
  override def concat(other: => ConfigurationUpdater): ConfigurationUpdater = this

  override def update(config: HierarchicalConfiguration, model: MediumTreeModel):
  HierarchicalConfiguration = {
    artists foreach { artist =>
      config clearTree artist
      model albumsFor artist foreach { a =>
        config.addProperty(MediumTreeModel toConfigKey a, a.key)
      }
    }
    config
  }
}
