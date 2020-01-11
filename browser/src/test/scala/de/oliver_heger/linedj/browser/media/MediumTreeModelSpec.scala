/*
 * Copyright 2015-2020 The Developers Team.
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
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import org.apache.commons.configuration.event.{ConfigurationEvent, ConfigurationListener}
import org.apache.commons.configuration.tree.DefaultExpressionEngine
import org.apache.commons.configuration.{AbstractConfiguration, HierarchicalConfiguration}
import org.scalatest.{FlatSpec, Matchers}

object MediumTreeModelSpec {
  /** Constant for an artist name. */
  private val Artist = "Pink Floyd"

  /** Constant for an album. */
  private val Album = "The Dark Side of the Moon"

  /** A key for the test album. */
  private val Key = AlbumKey(Artist, Album)

  /**
   * A special configuration event listener implementation. All events
   * received after a configuration modification are stored in a list.
   */
  private class EventListener extends ConfigurationListener {
    /** The list with received events (in reverse order). */
    var events = List.empty[ConfigurationEvent]

    override def configurationChanged(configurationEvent: ConfigurationEvent): Unit = {
      if (!configurationEvent.isBeforeUpdate) {
        events = configurationEvent :: events
      }
    }
  }

  /**
   * Creates a new configuration object and initializes it to be used by the
   * tests.
   * @return the new configuration
   */
  private def createConfiguration(): HierarchicalConfiguration = {
    val config = new HierarchicalConfiguration
    val engine = new DefaultExpressionEngine
    engine setPropertyDelimiter "|"
    config setExpressionEngine engine
    config
  }

  /**
   * Creates a new event listener and registers it at the specified
   * configuration.
   * @param config the configuration
   * @return the new event listener
   */
  private def installListener(config: HierarchicalConfiguration): EventListener = {
    val listener = new EventListener
    config addConfigurationListener listener
    listener
  }

  /**
   * Creates a ''SongData'' object for the specified meta data.
   * @param meta the meta data
   * @return the corresponding ''SongData''
   */
  private def song(meta: MediaMetaData): SongData =
    SongData(MediaFileID(MediumID("someURI", None), "testURIDontCare"), meta,
      meta.title getOrElse "", meta.artist getOrElse "", meta.album getOrElse "")
}

/**
 * Test class for ''MediumTreeModel''.
 */
class MediumTreeModelSpec extends FlatSpec with Matchers {

  import MediumTreeModelSpec._

  /**
   * Processes a list of meta data items on the given configuration. A new
   * empty is created, and then the items are added one by one. The resulting
   * updater is finally applied on the configuration.
   * @param config the configuration
   * @param items the list of meta data items
   * @return a tuple with the resulting model and updater
   */
  private def processMetaData(config: HierarchicalConfiguration,
                              items: List[(AlbumKey, SongData)]): (MediumTreeModel,
    ConfigurationUpdater) = {
    updateConfiguration(config, items.foldLeft((MediumTreeModel.empty, NoopUpdater:
      ConfigurationUpdater)) { (m, i) =>
      m._1.add(i._1, i._2, m._2)
    })
  }

  /**
   * Checks whether the test artist in the given configuration has the expected
   * albums.
   * @param config the configuration
   * @param expected a list with the expected albums
   * @param artist the name of the artist to be checked
   * @return a flag whether the check was successful
   */
  private def checkAlbumKeys(config: HierarchicalConfiguration, expected: List[AlbumKey],
                             artist: String = Artist): Boolean = {
    val artistNode = config.configurationAt(artist).getRootNode
    if (artistNode.getChildrenCount == expected.size) {
      import collection.JavaConversions._
      val nodesWithItems = artistNode.getChildren zip expected
      nodesWithItems forall { e =>
        e._1.getValue == e._2 && e._1.getName.endsWith(e._2.album)
      }
    } else false
  }

  /**
   * Convenience function for checking the albums of the test artist in the
   * given configuration against an item list. The relevant album keys are
   * extracted first.
   * @param config the configuration
   * @param expected a list with the expected album keys and meta data
   * @return a flag whether the check was successful
   */
  private def checkAlbumKeysWithItems(config: HierarchicalConfiguration, expected: List[
    (AlbumKey, SongData)]): Boolean =
    checkAlbumKeys(config, expected map (_._1))

  /**
   * Applies the changes on a model on the given configuration object.
   * @param config the configuration
   * @param model the tuple returned by the model
   * @return the tuple returned by the model
   */
  private def updateConfiguration(config: HierarchicalConfiguration,
                                  model: (MediumTreeModel, ConfigurationUpdater)):
  (MediumTreeModel, ConfigurationUpdater) = {
    model._2.update(config, model._1) should be(config)
    model
  }

  "A MediumTreeModel" should "add information for a new artist" in {
    val config = createConfiguration()
    val listener = installListener(config)
    val items = List((Key, song(MediaMetaData())))

    processMetaData(config, items)
    listener.events should have size 1
    listener.events.head.getType should be(AbstractConfiguration.EVENT_ADD_PROPERTY)
    checkAlbumKeysWithItems(config, items) shouldBe true
  }

  it should "ignore an already existing album" in {
    val config = createConfiguration()
    val model = MediumTreeModel.empty
    val (model1, updater1) = model.add(Key, song(MediaMetaData(title = Some("Time"))), NoopUpdater)

    val listener = installListener(config)
    val (model2, updater2) = updateConfiguration(config, model1.add(Key,
      song(MediaMetaData(title = Some("Brain Damage"))), updater1))
    model2 should be(model1)
    updater2 should be(updater1)
    listener.events should have size 1
    checkAlbumKeys(config, List(Key)) shouldBe true
  }

  it should "append another album for an artist" in {
    val Key2 = AlbumKey(Artist, "The Wall")
    val items = List((Key, song(MediaMetaData(inceptionYear = Some(1973)))),
      (Key2, song(MediaMetaData(inceptionYear = Some(1979)))))
    val config = createConfiguration()

    processMetaData(config, items)
    checkAlbumKeysWithItems(config, items) shouldBe true
  }

  it should "order albums by year" in {
    val Key2 = AlbumKey(Artist, "Atom Heart Mother")
    val Key3 = AlbumKey(Artist, "A Momentary Lapse of Reason")
    val items = List((Key, song(MediaMetaData(inceptionYear = Some(1973)))),
      (Key2, song(MediaMetaData(inceptionYear = Some(1970)))),
      (Key3, song(MediaMetaData(inceptionYear = Some(1987)))))
    val config = createConfiguration()

    processMetaData(config, items)
    checkAlbumKeys(config, List(Key2, Key, Key3)) shouldBe true
  }

  it should "order albums by name if no year is available" in {
    val Key2 = AlbumKey(Artist, "Atom Heart Mother")
    val Key3 = AlbumKey(Artist, "A Momentary Lapse of Reason")
    val meta = song(MediaMetaData())
    val items = List((Key, meta), (Key2, meta), (Key3, meta))
    val config = createConfiguration()

    processMetaData(config, items)
    checkAlbumKeys(config, List(Key3, Key2, Key)) shouldBe true
  }

  it should "add the year to the album if available" in {
    val config = createConfiguration()
    val items = List((Key, song(MediaMetaData(inceptionYear = Some(1973)))))
    processMetaData(config, items)

    config containsKey Artist + "|(1973) " + Album shouldBe true
  }

  it should "add no year to the album key if no year is available" in {
    val config = createConfiguration()
    val items = List((Key, song(MediaMetaData())))
    processMetaData(config, items)

    config containsKey Artist + "|" + Album shouldBe true
  }

  it should "allow creating a complete model and updating a configuration at once" in {
    val Key2 = AlbumKey(Artist, "Atom Heart Mother")
    val Key3 = AlbumKey(Artist, "A Momentary Lapse of Reason")
    val Artist2 = "Dire Straits"
    val Key4 = AlbumKey(Artist2, "Love over Gold")
    val Key5 = AlbumKey(Artist2, "Brothers in Arms")
    val meta = song(MediaMetaData())
    val items = List((Key, meta), (Key5, song(MediaMetaData(inceptionYear = Some(1985)))),
      (Key3, meta), (Key2, song(MediaMetaData(inceptionYear = Some(1970)))),
      (Key4, song(MediaMetaData(inceptionYear = Some(1982)))))

    val model = MediumTreeModel(items)
    val updater = model.fullUpdater()
    val config = updater.update(createConfiguration(), model)
    checkAlbumKeys(config, List(Key2, Key3, Key)) shouldBe true
    checkAlbumKeys(config, List(Key4, Key5), Artist2) shouldBe true
  }
}
