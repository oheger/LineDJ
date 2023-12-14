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

import de.oliver_heger.linedj.platform.ActionTestHelper
import de.oliver_heger.linedj.platform.app.ConsumerRegistrationProviderTestHelper
import de.oliver_heger.linedj.platform.audio.model.{SongData, SongDataFactory}
import de.oliver_heger.linedj.platform.audio.{AudioPlayerState, AudioPlayerStateChangeRegistration, AudioPlayerStateChangedEvent}
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.ext.ArchiveAvailabilityExtension.ArchiveAvailabilityRegistration
import de.oliver_heger.linedj.platform.mediaifc.ext.AvailableMediaExtension.AvailableMediaRegistration
import de.oliver_heger.linedj.platform.mediaifc.ext.MetaDataCache.{MediumContent, MetaDataRegistration, RemoveMetaDataRegistration}
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediaFileID, MediumID, MediumInfo}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.WidgetHandler
import net.sf.jguiraffe.gui.builder.components.model.*
import org.apache.commons.configuration.HierarchicalConfiguration
import org.apache.commons.configuration.tree.{ConfigurationNode, DefaultConfigurationNode, DefaultExpressionEngine}
import org.mockito.ArgumentMatchers.{any, anyInt}
import org.mockito.Mockito
import org.mockito.Mockito.*
import org.mockito.invocation.InvocationOnMock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import java.util
import java.util.Locale
import scala.annotation.tailrec
import scala.collection.immutable.Seq
import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag
import scala.util.Random

object MediaControllerSpec:
  /** A prefix for URIs for test media. */
  private val MediumUriPrefix = "media://"

  /** Constant for a medium name. */
  private val Medium = "Rock1"

  /** Constant for a test medium ID. */
  private val TestMediumID = mediumID(Medium)

  /** A list with the sorted names of available media. */
  private val MediaNames = List(Medium, "Rock2", "Rock3")

  /** The name for the undefined medium. */
  private val UndefinedMediumName = "The undefined medium!"

  /** URI for a specific undefined medium. */
  private val UndefinedMediumUri = "anUndefinedMedium"

  /** The message with the available media. */
  private val AvailableMediaMsg = createAvailableMediaMsg()

  /** Constant for an artist name. */
  private val Artist1 = "Dire Straits"

  /** Another artist. */
  private val Artist2 = "Kate Bush"

  /** Constant for the 1st test album. */
  private val Album1 = "Money for Nothing"

  /** Constant for the 2nd test album. */
  private val Album2 = "Live at the BBC"

  /** And a 3rd test album. */
  private val Album3 = "Hounds of Love"

  /** The songs of the first test album. */
  private val Songs1 = Vector("Sultans of Swing", "Down to the Waterline", "Portobello Bell")

  /** The songs of the second test album. */
  private val Songs2 = Vector("Down to the Waterline", "Six Blade Knife", "Water of Love")

  /** The songs of the third test album. */
  private val Songs3 = Vector("Running up that Hill", "Hounds of Love", "The Big Sky")

  /** Names of the actions for adding songs to the playlist. */
  private val AppendActions = List("addMediumAction", "addArtistAction", "addAlbumAction",
    "addSongsAction")

  /**
    * Returns the ID for a test medium based on its name.
    *
    * @param name the name of the medium
    * @return the corresponding test medium ID
    */
  private def mediumID(name: String): MediumID = MediumID(MediumUriPrefix + name,
    Some(Paths.get(name).toString))

  /**
    * Extracts the name of a test medium from the given medium ID. If the
    * medium URI has not the expected form, ''None'' is returned.
    *
    * @param mid the medium ID
    * @return an option with the extracted name of this test medium
    */
  private def mediumName(mid: MediumID): Option[String] =
    if mid.mediumURI startsWith MediumUriPrefix then
      Some(mid.mediumURI.substring(MediumUriPrefix.length))
    else None

  /**
    * Transforms the given string to upper case.
    *
    * @param s the string
    * @return the string in upper case
    */
  private def toUpper(s: String): String = s toUpperCase Locale.ROOT

  /**
    * Generates the name of a test archive component based on the medium root
    * URI.
    *
    * @param mediumUri the medium URI
    * @return the test archive component name
    */
  private def archiveComponentName(mediumUri: String): String = "archive_component_" + mediumUri

  /**
    * Creates a medium info object with dummy property values for the specified
    * medium ID.
    *
    * @param id the medium ID
    * @return the undefined medium info
    */
  private def undefinedMediumInfo(id: MediumID): MediumInfo =
    MediumInfo(name = "(undefined)", description = null, mediumID = id, orderMode = null, checksum = "nocheck")

  /**
    * Creates a medium info object for a medium without a description file.
    *
    * @param uri the URI of the medium
    * @return the undefined medium info
    */
  private def undefinedMediumInfo(uri: String): MediumInfo =
    undefinedMediumInfo(MediumID(uri, None, archiveComponentName(uri)))

  /**
    * Generates a mapping for a medium info object.
    *
    * @param info the medium info object
    * @return the mapping
    */
  private def infoMapping(info: MediumInfo): (MediumID, MediumInfo) = (info.mediumID, info)

  /**
    * Creates the message with available media based on the list of media
    * names.
    *
    * @return the message for available media
    */
  private def createAvailableMediaMsg(): AvailableMedia =
    val definedMappings = MediaNames map { m =>
      (mediumID(m), mediumInfo(m))
    }
    val undefinedMappings = List(infoMapping(undefinedMediumInfo(UndefinedMediumUri)), infoMapping
    (undefinedMediumInfo("anotherUndefinedURI")))
    val mappings = Random.shuffle(List(definedMappings, undefinedMappings).flatten)
    AvailableMedia(List(mappings: _*))

  /**
    * Creates a test ''MediumInfo'' object.
    *
    * @param name the medium name
    * @return the mock for this medium info
    */
  private def mediumInfo(name: String): MediumInfo =
    MediumInfo(name = name, description = name + "_desc", mediumID = mediumID(name),
      checksum = mediumChecksum(name), orderMode = "")

  /**
    * Generates a checksum for a test medium.
    *
    * @param name the name of the medium
    * @return the checksum for this test medium
    */
  private def mediumChecksum(name: String): String =
    name + "_checksum"

  /**
    * Creates ''SongData'' objects for a test album.
    *
    * @param artist   the artist
    * @param album    the album name
    * @param songs    the sequence of songs on this album
    * @param mediumID an optional medium ID
    * @return a corresponding sequence of ''SongData'' objects
    */
  private def createSongData(artist: String, album: String, songs: Seq[String],
                             mediumID: MediumID = TestMediumID): Seq[SongData] =
    songs.zipWithIndex.map(e => SongData(MediaFileID(mediumID, "song://" + album + "/" + e._1,
      mediumName(mediumID) map mediumChecksum),
      MediaMetaData(title = Some(e._1), artist = Some(artist), album = Some(album),
        trackNumber = Some(e._2)), e._1, artist, album))

  /**
    * Creates a ''MediumContent'' object from the specified data.
    *
    * @param complete the complete flag
    * @param songs    a sequence with the songs
    * @return the content object
    */
  private def createContent(complete: Boolean = false, songs: Seq[SongData]) =
    val mappings = songs map (s => (s.id, s.metaData))
    MediumContent(complete = complete, data = mappings.toMap)

  /**
    * Creates a ''TreeNodePath'' for the specified configuration node.
    *
    * @param node the configuration node
    * @return the ''TreeNodePath'' for this node
    */
  private def createTreePath(node: ConfigurationNode): TreeNodePath = new TreeNodePath(node)

  /**
    * Creates a ''TreeNodePath'' that points to a node representing the
    * specified album.
    *
    * @param artist the artist
    * @param album  the album
    * @return the ''TreeNodePath'' for this album
    */
  private def createTreePath(artist: String, album: String): TreeNodePath =
    val key = AlbumKey(toUpper(artist), toUpper(album))
    val node = new DefaultConfigurationNode(key.album)
    node setValue key
    createTreePath(node)

  /**
    * Sends a change event about an updated audio player state to the
    * specified controller.
    *
    * @param controller     the controller
    * @param playlistClosed flag whether the playlist is closed
    */
  private def sendPlaylistState(controller: MediaController, playlistClosed: Boolean): Unit =
    val reg = ConsumerRegistrationProviderTestHelper
      .findRegistration[AudioPlayerStateChangeRegistration](controller)
    val event = AudioPlayerStateChangedEvent(AudioPlayerState(playlist = null, playlistSeqNo = 0,
      playbackActive = true, playlistClosed = playlistClosed, playlistActivated = true))
    reg.callback(event)

/**
  * Test class for ''MediaController''.
  */
class MediaControllerSpec extends AnyFlatSpec with Matchers:

  import MediaControllerSpec._

  "A MediaController" should "use correct IDs in consumer registrations" in:
    val helper = new MediaControllerTestHelper
    ConsumerRegistrationProviderTestHelper.checkRegistrationIDs(helper.controller)

  it should "disable the combo when the archive is unavailable" in:
    val helper = new MediaControllerTestHelper

    helper sendArchiveStateEvent MediaFacade.MediaArchiveUnavailable
    verify(helper.comboHandler).setEnabled(false)
    verify(helper.labelInProgress).setVisible(false)

  it should "handle an archive available message correctly" in:
    val helper = new MediaControllerTestHelper

    helper sendArchiveStateEvent MediaFacade.MediaArchiveAvailable
    verify(helper.labelInProgress).setVisible(false)

  it should "pass available media to the combo handler" in:
    val helper = new MediaControllerTestHelper

    helper.sendDefaultAvailableMedia()
    val verInOrder = Mockito.inOrder(helper.comboHandler)
    verInOrder.verify(helper.comboHandler).removeItem(2)
    verInOrder.verify(helper.comboHandler).removeItem(1)
    verInOrder.verify(helper.comboHandler).removeItem(0)
    verInOrder.verify(helper.comboHandler).addItem(0, Medium, TestMediumID)
    verInOrder.verify(helper.comboHandler).addItem(1, MediaNames(1), mediumID(MediaNames(1)))
    verInOrder.verify(helper.comboHandler).addItem(2, MediaNames(2), mediumID(MediaNames(2)))
    verInOrder.verify(helper.comboHandler).addItem(3, UndefinedMediumName, MediumID.UndefinedMediumID)
    verInOrder.verify(helper.comboHandler).setData(TestMediumID)
    verInOrder.verify(helper.comboHandler).setEnabled(true)

  it should "handle an empty map with media correctly" in:
    val helper = new MediaControllerTestHelper
    helper prepareMediaListModel 3

    helper sendAvailableMedia AvailableMedia(List.empty)
    verify(helper.comboHandler, never()).setData(any())
    verify(helper.comboHandler, never()).setEnabled(true)

  it should "add an entry for the undefined medium only if it exists" in:
    val mediaMap = AvailableMediaMsg.mediaList filter (e => e._1.mediumDescriptionPath.isDefined)
    val helper = new MediaControllerTestHelper
    helper prepareMediaListModel 0

    helper sendAvailableMedia AvailableMedia(mediaMap)
    verify(helper.comboHandler, never()).removeItem(anyInt())
    verify(helper.comboHandler, never()).addItem(3, UndefinedMediumName, MediumID.UndefinedMediumID)

  it should "query meta data for a newly selected medium" in:
    val helper = new MediaControllerTestHelper

    helper.selectMedium()
    helper.findMessageType[RemoveMetaDataRegistration] shouldBe empty

  it should "define a correct component ID" in:
    val helper = new MediaControllerTestHelper

    helper.controller.componentID should not be null

  it should "remove a previous meta data registration" in:
    val oldMedium = mediumID("someMedium")
    val helper = new MediaControllerTestHelper
    helper selectMedium oldMedium
    helper.clearReceivedMessages()

    helper.selectMedium()
    helper expectMessage RemoveMetaDataRegistration(oldMedium, helper.controller.componentID)

  it should "not remove a meta data registration when receiving new media" in:
    val helper = new MediaControllerTestHelper
    helper prepareMediaListModel 0
    helper.selectMedium()
    helper.clearReceivedMessages()

    helper sendAvailableMedia AvailableMediaMsg
    helper selectMedium mediumID(MediaNames(1))
    helper.findMessageType[RemoveMetaDataRegistration] shouldBe empty

  it should "clear the tree model when another medium is selected" in:
    val helper = new MediaControllerTestHelper
    helper.treeModel.setProperty("someKey", "someValue")

    helper selectMedium mediumID(MediaNames(1))
    helper.treeModel should not be empty
    helper.clearReceivedMessages()

    helper.selectMedium()
    helper.treeModel shouldBe empty

  it should "set the root node of the tree model to the medium name" in:
    val helper = new MediaControllerTestHelper
    helper prepareMediaListModel 0
    helper sendAvailableMedia AvailableMediaMsg
    helper.selectMedium()

    helper.treeModel.getRootNode.getName should be(Medium)

  it should "use the undefined name for an unknown medium ID" in:
    val helper = new MediaControllerTestHelper
    helper prepareMediaListModel 0
    helper sendAvailableMedia AvailableMediaMsg
    helper.selectMedium(MediumID("unknown medium", None))

    helper.treeModel.getRootNode.getName should be(UndefinedMediumName)

  it should "use the undefined name for the undefined medium ID" in:
    val helper = new MediaControllerTestHelper
    helper prepareMediaListModel 0
    helper sendAvailableMedia AvailableMediaMsg
    helper.selectMedium(MediumID.UndefinedMediumID)

    helper.treeModel.getRootNode.getName should be(UndefinedMediumName)

  it should "clear the table model when another medium is selected" in:
    val helper = new MediaControllerTestHelper
    helper.tableModel add "someData"

    helper selectMedium mediumID(MediaNames(1))
    helper.tableModel should not be empty
    helper.clearReceivedMessages()

    helper.selectMedium()
    helper.tableModel shouldBe empty
    verify(helper.tableHandler).tableDataChanged()

  it should "enable the add medium action when a medium is selected" in:
    val helper = new MediaControllerTestHelper

    helper.selectMedium()
    helper.verifyAction("addMediumAction", enabled = true)

  it should "only enable the add medium action if the playlist is not closed" in:
    val helper = new MediaControllerTestHelper

    helper.closePlaylist()
      .selectMedium()
    helper.verifyAction("addMediumAction", enabled = false)

  it should "populate the tree model when meta data arrives" in:
    val helper = new MediaControllerTestHelper
    val content = createContent(songs = createSongData(Artist1, Album1, Songs1))

    helper selectMediumAndSendMeta content
    helper.expectAlbumInTreeModel(Artist1, Album1)

  it should "process multiple chunks of data" in:
    val helper = new MediaControllerTestHelper
    val content1 = createContent(songs = createSongData(Artist1, Album1, Songs1))
    val content2 = createContent(songs = createSongData(Artist1, Album2, Songs2))

    val callback = helper selectMediumAndSendMeta content1
    callback(content2)
    helper.expectAlbumInTreeModel(Artist1, Album1)
    helper.expectAlbumInTreeModel(Artist1, Album2)

  it should "process chunks with multiple artists" in:
    val ArtistPrefix = "TestArtist"
    val AlbumPrefix = "TestAlbum"
    val ArtistCount = 8

    def createSyntheticSongData(artistIndex: Int): Seq[SongData] =
      createSongData(ArtistPrefix + artistIndex, AlbumPrefix + "1", List("Song" + artistIndex)) ++
        createSongData(ArtistPrefix + artistIndex, AlbumPrefix + "2", List("OtherSong" +
          artistIndex))

    @tailrec
    def appendSyntheticSongData(songs: Seq[SongData], index: Int): Seq[SongData] =
      if index >= ArtistCount then songs
      else appendSyntheticSongData(songs ++ createSyntheticSongData(index), index + 1)

    val songsOfArtist1 = createSongData(Artist1, Album1, Songs1) ++ createSongData(Artist1,
      Album2, Songs2)
    val songsOfArtist2 = createSongData(Artist2, Album3, Songs3)
    val allSongs = appendSyntheticSongData(songsOfArtist1 ++ songsOfArtist2, 1)
    val content = createContent(songs = allSongs)
    val helper = new MediaControllerTestHelper
    val callback = helper selectMediumAndSendMeta createContent(songs = Nil)

    callback(content)
    helper.expectAlbumInTreeModel(Artist1, Album1)
    helper.expectAlbumInTreeModel(Artist1, Album2)
    helper.expectAlbumInTreeModel(Artist2, Album3)

  it should "ignore a chunk for a medium which is not currently selected" in:
    val helper = new MediaControllerTestHelper
    val callback = helper.selectMedium()

    helper.selectMedium(mediumID("other"))
    callback(createContent(songs = createSongData(Artist1, Album1, Songs1)))
    helper.treeModel shouldBe empty

  it should "ignore meta data chunks if no medium is selected" in:
    val helper = new MediaControllerTestHelper
    helper prepareMediaListModel 0
    val callback = helper.selectMedium()
    helper sendAvailableMedia AvailableMediaMsg

    callback(createContent(songs = createSongData(Artist1, Album1,
          Songs1)))
    helper.treeModel shouldBe empty

  it should "fill the table model when an album is selected" in:
    val songs = createSongData(Artist1, Album1, Songs1)
    val content = createContent(songs = songs)
    val helper = new MediaControllerTestHelper
    helper.sendDefaultAvailableMedia()
      .selectMediumAndSendMeta(content)

    helper selectAlbums createTreePath(Artist1, Album1)
    verify(helper.tableHandler).tableDataChanged()
    helper expectSongsInTable songs

  /**
    * Creates a test helper and prepares it for a test which uses an album
    * selection.
    *
    * @return the prepared test helper
    */
  private def prepareAlbumSelection(): MediaControllerTestHelper =
    val songs = createSongData(Artist1, Album1, Songs1)
    val helper = new MediaControllerTestHelper
    helper selectMediumAndSendMeta createContent(songs = songs)
    helper

  it should "enable append actions for artist and album when there is a selection" in:
    val helper = prepareAlbumSelection()

    helper selectAlbums createTreePath(Artist1, Album1)
    helper.verifyAction("addArtistAction", enabled = true)
      .verifyAction("addAlbumAction", enabled = true)

  it should "disable append actions if there is no album and artist selection" in:
    val helper = prepareAlbumSelection()

    helper.selectAlbums()
    helper.verifyAction("addArtistAction", enabled = false)
      .verifyAction("addAlbumAction", enabled = false)

  it should "enable append actions correctly if there is an artist, but no album selection" in:
    val helper = prepareAlbumSelection()
    val node = new DefaultConfigurationNode(Artist1)

    helper selectAlbums createTreePath(node)
    helper.verifyAction("addArtistAction", enabled = true)
      .verifyAction("addAlbumAction", enabled = false)

  it should "not enable append actions for artist and album if the playlist is not open" in:
    val helper = prepareAlbumSelection()

    helper.closePlaylist()
      .selectAlbums(createTreePath(Artist1, Album1))
    helper.verifyAction("addArtistAction", enabled = false)
      .verifyAction("addAlbumAction", enabled = false)

  it should "disable the append songs action if no songs are selected" in:
    val helper = new MediaControllerTestHelper
    when(helper.tableHandler.getSelectedIndices).thenReturn(Array.empty[Int])

    helper.controller.songSelectionChanged()
    helper.verifyAction("addSongsAction", enabled = false)

  it should "enable the append songs action if songs are selected" in:
    val helper = new MediaControllerTestHelper
    when(helper.tableHandler.getSelectedIndices).thenReturn(Array(1, 2))

    helper.controller.songSelectionChanged()
    helper.verifyAction("addSongsAction", enabled = true)

  it should "not enable the append songs action if the playlist is not open" in:
    val helper = new MediaControllerTestHelper
    when(helper.tableHandler.getSelectedIndices).thenReturn(Array(1, 2))

    helper.closePlaylist()
    helper.controller.songSelectionChanged()
    helper.verifyAction("addSongsAction", enabled = false)

  it should "disable all actions when the playlist is closed" in:
    val helper = prepareAlbumSelection()
    when(helper.tableHandler.getSelectedIndices).thenReturn(Array(1, 2))
    helper.selectAlbums(createTreePath(Artist1, Album1))
    helper.controller.songSelectionChanged()

    helper.closePlaylist()
      .verifyAction("addArtistAction", enabled = false)
      .verifyAction("addAlbumAction", enabled = false)
      .verifyAction("addMediumAction", enabled = false)
      .verifyAction("addSongsAction", enabled = false)

  it should "only disable add song actions if the playlist is actually closed" in:
    val helper = new MediaControllerTestHelper
    when(helper.tableHandler.getSelectedIndices).thenReturn(Array(1, 2))

    helper.controller.songSelectionChanged()
    sendPlaylistState(helper.controller, playlistClosed = false)
    helper.verifyAction("addSongsAction", enabled = true)

  it should "enable actions again when the playlist is re-opened" in:
    val helper = prepareAlbumSelection()
    helper selectAlbums createTreePath(Artist1, Album1)
    when(helper.tableHandler.getSelectedIndices).thenReturn(Array(1, 2))
    helper.controller.songSelectionChanged()
    helper.closePlaylist()

    helper.changePlaylistOpenState(playlistClosed = false)
      .verifyAction("addArtistAction", enabled = true)
      .verifyAction("addAlbumAction", enabled = true)
      .verifyAction("addMediumAction", enabled = true)
      .verifyAction("addSongsAction", enabled = true)

  it should "update the table model for multiple chunks" in:
    val songs1 = createSongData(Artist1, Album1, Songs1)
    val songs2 = createSongData(Artist2, Album3, Songs3)
    val content1 = createContent(songs = songs1)
    val content2 = createContent(songs = songs2)
    val helper = new MediaControllerTestHelper
    val callback = helper.sendDefaultAvailableMedia()
      .selectMediumAndSendMeta(content1)
    callback(content2)

    helper selectAlbums createTreePath(Artist2, Album3)
    helper expectSongsInTable songs2

  it should "clear the table model when the selection is changed" in:
    val songs = createSongData(Artist1, Album1, Songs1) ++ createSongData(Artist2, Album3, Songs3)
    val helper = new MediaControllerTestHelper
    helper selectMediumAndSendMeta createContent(songs = songs)

    helper selectAlbums createTreePath(Artist1, Album1)
    helper selectAlbums createTreePath(Artist2, Album3)
    helper.tableModel.size() should be(Songs3.size)

  it should "ignore an album selection if no model is initialized" in:
    val helper = new MediaControllerTestHelper
    helper.selectMedium()

    helper selectAlbums createTreePath(Artist1, Album1)
    helper.tableModel shouldBe empty

  it should "support multiple selected album paths" in:
    val songs1 = createSongData(Artist1, Album1, Songs1)
    val songs2 = createSongData(Artist1, Album2, Songs2)
    val songs3 = createSongData(Artist2, Album3, Songs3)
    val content = createContent(songs = songs1 ++ songs2 ++ songs3)
    val helper = new MediaControllerTestHelper
    helper.sendDefaultAvailableMedia()
      .selectMediumAndSendMeta(content)

    helper.selectAlbums(createTreePath(Artist1, Album1),
      createTreePath(new DefaultConfigurationNode),
      createTreePath(Artist1, Album2),
      createTreePath(new DefaultConfigurationNode))
    helper expectSongsInTable songs1
    helper expectSongsInTable songs2
    verify(helper.tableHandler).tableDataChanged()

  it should "update the table model if it is affected by a received chunk" in:
    val helper = new MediaControllerTestHelper
    val callback = helper selectMediumAndSendMeta createContent(songs =
          createSongData(Artist1, Album1, Songs1 take 1))
    helper selectAlbums createTreePath(Artist1, Album1)
    verify(helper.tableHandler).tableDataChanged()

    callback(createContent(songs = createSongData(Artist1, Album1, Songs1 drop 1)))
    verify(helper.tableHandler, times(2)).tableDataChanged()
    helper.tableModel.size() should be(Songs1.size)

  it should "reset all models when the medium selection changes" in:
    val OtherName = "_other"
    val OtherMedium = mediumID(OtherName)
    val songs1 = createSongData(Artist1, Album1, Songs1)
    val songs2 = createSongData(Artist1, Album2, Songs2 take 1)
    val songs3 = createSongData(Artist1, Album2, Songs2 drop 1, mediumID = OtherMedium)
    val helper = new MediaControllerTestHelper
    helper prepareMediaListModel 4
    helper sendAvailableMedia AvailableMediaMsg
      .copy(mediaList = (OtherMedium, mediumInfo(OtherName)) :: AvailableMediaMsg.mediaList)
    helper selectMediumAndSendMeta createContent(songs = songs1 ++ songs2)
    helper selectAlbums createTreePath(Artist1, Album2)
    helper.clearReceivedMessages()

    helper selectMediumAndSendMeta createContent(songs = songs3)
    helper selectAlbums createTreePath(Artist1, Album2)
    helper.expectAlbumInTreeModel(Artist1, Album2)
    helper expectSongsInTable songs3
    val artistNode = helper.treeModel.getRootNode.getChild(0)
    artistNode.getChildrenCount should be(1)
    helper.tableModel.size() should be(songs3.size)
    verify(helper.tableHandler, times(3)).tableDataChanged()

  it should "display the in-progress indicator after a medium selection" in:
    val helper = new MediaControllerTestHelper
    helper.selectMedium()

    verify(helper.labelInProgress).setVisible(true)

  it should "hide the in-progress indicator when the last data chunk was received" in:
    val content1 = createContent(songs = createSongData(Artist1, Album1, Songs1))
    val content2 = createContent(complete = true, songs = createSongData(Artist2, Album3, Songs3))
    val helper = new MediaControllerTestHelper
    val callback = helper selectMediumAndSendMeta content1

    verify(helper.labelInProgress, never()).setVisible(false)
    callback(content2)
    verify(helper.labelInProgress).setVisible(false)

  /**
    * Adds all test songs to the controller.
    *
    * @param helper the helper
    * @return the list of songs that have been added
    */
  private def addAllSongsToController(helper: MediaControllerTestHelper): List[Seq[SongData]] =
    val songsAlbum1 = createSongData(Artist1, Album1, Songs1)
    val songsAlbum2 = createSongData(Artist1, Album2, Songs2)
    val songsAlbum3 = createSongData(Artist2, Album3, Songs3)
    val songs = songsAlbum1 ++ songsAlbum2 ++ songsAlbum3
    helper selectMediumAndSendMeta createContent(songs = songs)
    List(songsAlbum2, songsAlbum1, songsAlbum3)

  it should "return the songs of the currently selected albums" in:
    val helper = new MediaControllerTestHelper
    val songs = addAllSongsToController(helper.sendDefaultAvailableMedia())
    helper.selectAlbums(createTreePath(Artist2, Album3), createTreePath(Artist1, Album1))

    helper.controller.songsForSelectedAlbums should be(songs(1) ++ songs(2))

  it should "ignore a request for songs if there is no model available" in:
    val helper = new MediaControllerTestHelper

    helper.controller.songsForSelectedAlbums should have size 0

  it should "return the songs of the currently selected artists" in:
    val helper = new MediaControllerTestHelper
    val songs = addAllSongsToController(helper.sendDefaultAvailableMedia())
    helper.selectAlbums(createTreePath(Artist2, Album3),
      createTreePath(helper.treeModel.getRootNode.getChild(0)))

    helper.controller.songsForSelectedArtists should be(songs.flatten)

  it should "return the songs of the current medium" in:
    val helper = new MediaControllerTestHelper
    val songs = addAllSongsToController(helper.sendDefaultAvailableMedia())

    helper.controller.songsForSelectedMedium should be(songs.flatten)

  it should "handle a duplicate album when accessing songs" in:
    val helper = new MediaControllerTestHelper
    val songsAlbum1 = createSongData(Artist1, Album1, Songs1)
    val songsAlbum3 = createSongData(Artist2, Album3, Songs3)
    val songs = songsAlbum1 ++ songsAlbum3
    val artist = toUpper(Artist1)
    val album = toUpper(Album1)
    helper selectMediumAndSendMeta createContent(songs = songs)
    helper.treeModel.addProperty(artist + "(-1)|" + album, AlbumKey(artist, album))

    helper.controller.songsForSelectedMedium // must not throw

  it should "disable all actions in its initialize method" in:
    val helper = new MediaControllerTestHelper

    helper.controller.initialize()
    helper.verifyAction("addMediumAction", enabled = false)
    helper.verifyAction("addArtistAction", enabled = false)
    helper.verifyAction("addAlbumAction", enabled = false)
    helper.verifyAction("addSongsAction", enabled = false)

  it should "add correct checksums to media file IDs" in:
    val songName = Songs1.head
    val orgFileID = MediaFileID(mediumID(Medium), songName)
    val processedID = orgFileID.copy(checksum = Some(AvailableMediaMsg.media(orgFileID.mediumID).checksum))
    val song = SongData(orgFileID, MediaMetaData(title = Some(songName), artist = Some(Artist1),
      album = Some(Album1)), songName, Artist1, Album1)
    val helper = new MediaControllerTestHelper
    helper.sendDefaultAvailableMedia()
    helper selectMediumAndSendMeta createContent(complete = true, songs = Seq(song))

    val songs = helper.controller.songsForSelectedMedium
    songs should have size 1
    val selSong = songs.head
    selSong.id should be(processedID)

  /**
    * A test helper class managing mock objects for the dependencies of a
    * controller.
    */
  private class MediaControllerTestHelper extends ActionTestHelper with MockitoSugar:

    import ConsumerRegistrationProviderTestHelper.*

    val songFactory: SongDataFactory = (id: MediaFileID, metaData: MediaMetaData) =>
      SongData(id, metaData, metaData.title.get, metaData.artist.get, metaData.album.get)

    /** A mock for the message bus. */
    val messageBus: MessageBus = createMessageBusMock()

    /** A mock for the media facade. */
    val mediaFacade: MediaFacade = createMediaFacade(messageBus)

    /** The mock for the combo handler. */
    val comboHandler: ListComponentHandler = mock[ListComponentHandler]

    /** The configuration acting as model for the tree view. */
    val treeModel: HierarchicalConfiguration = createTreeModelConfig()

    /** The handler for the tree view. */
    val treeHandler: TreeHandler = createTreeHandler(treeModel)

    /** The model for the table control. */
    val tableModel = new util.ArrayList[AnyRef]

    /** The handler for the table. */
    val tableHandler: TableHandler = createTableHandler(tableModel)

    /** The in-progress widget. */
    val labelInProgress: WidgetHandler = mock[WidgetHandler]

    /** The mock action store. */
    val actionStore: ActionStore = initActions()

    /** The controller test instance. */
    val controller: MediaController = createController()

    /** Stores the messages published to the message bus. */
    private val publishedMessages = ListBuffer.empty[Any]

    /**
      * Sends the specified event to the corresponding consumer registered by
      * the test controller.
      *
      * @param ev the event
      */
    def sendArchiveStateEvent(ev: MediaFacade.MediaArchiveAvailabilityEvent): Unit =
      val reg = findRegistration[ArchiveAvailabilityRegistration](controller)
      reg.callback(ev)

    /**
      * Sends the specified ''AvailableMedia'' object to the corresponding
      * consumer registered by the test controller.
      *
      * @param am the message
      */
    def sendAvailableMedia(am: AvailableMedia): Unit =
      val reg = findRegistration[AvailableMediaRegistration](controller)
      reg.callback(am)

    /**
      * Clears the buffer with the messages published to the message bus.
      */
    def clearReceivedMessages(): Unit =
      publishedMessages.clear()

    /**
      * Verifies that the controller has registered for meta data of the
      * specified medium.
      *
      * @param mediumID the medium ID
      * @return the function for receiving meta data content objects
      */
    def verifyMetaDataRequest(mediumID: MediumID = TestMediumID): MediumContent => Unit =
      val regMsg = expectMessageType[MetaDataRegistration]
      regMsg.id should be(controller.componentID)
      regMsg.mediumID should be(mediumID)
      regMsg.callback

    /**
      * Creates a mock for a list model and installs it for the combo box.
      *
      * @param size the size to be returned by the list model
      * @return the list model mock
      */
    def prepareMediaListModel(size: Int): ListModel =
      val model = mock[ListModel]
      when(model.size()).thenReturn(size)
      when(comboHandler.getListModel).thenReturn(model)
      model

    /**
      * Sends the message with default available media to the test controller.
      *
      * @return this test helper
      */
    def sendDefaultAvailableMedia(): MediaControllerTestHelper =
      prepareMediaListModel(3)
      sendAvailableMedia(AvailableMediaMsg)
      this

    /**
      * Selects the specified medium in the controller and verifies that the
      * expected actions are performed. The controller should request the meta
      * data of this medium. The corresponding listener callback is returned.
      *
      * @param mediumID the medium ID
      * @return the function for receiving meta data content objects
      */
    def selectMedium(mediumID: MediumID = TestMediumID): MediumContent => Unit =
      controller selectMedium mediumID
      verifyMetaDataRequest(mediumID)

    /**
      * Convenience method for selecting a medium and sending a meta data
      * object for it. The meta data callback is returned.
      *
      * @param content the content of the medium
      * @param mediumID the ID of the medium to select
      * @return the function for receiving meta data
      */
    def selectMediumAndSendMeta(content: MediumContent, mediumID: MediumID = TestMediumID): MediumContent => Unit =
      val callback = selectMedium(mediumID)
      clearReceivedMessages()
      callback(content)
      callback

    /**
      * Selects the specified tree paths (representing artists or albums).
      *
      * @param paths the paths to be selected
      */
    def selectAlbums(paths: TreeNodePath*): Unit =
      controller selectAlbums paths.toArray

    /**
      * Searches for a message of the given type and returns an option with the
      * found instance.
      *
      * @param t the class tag
      * @tparam T the type of the message
      * @return an options with the found message instance
      */
    def findMessageType[T](implicit t: ClassTag[T]): Option[T] =
      val cls = t.runtimeClass
      publishedMessages.toList.findLast(cls.isInstance).map(_.asInstanceOf[T])

    /**
      * Expects that a message of the given type was published via the message
      * bus and returns the first occurrence.
      *
      * @param t the class tag
      * @tparam T the type of the desired message
      * @return the message of this type
      */
    def expectMessageType[T](implicit t: ClassTag[T]): T =
      val optMsg = findMessageType(t)
      optMsg shouldBe defined
      optMsg.get

    /**
      * Expects that the specified message was published via the message bus.
      *
      * @param msg the expected message
      * @tparam T the type of the message
      * @return the same message
      */
    def expectMessage[T](msg: T): T =
      publishedMessages should contain(msg)
      msg

    /**
      * Checks whether a specific album is stored in the tree model.
      *
      * @param artist the artist
      * @param album  the album
      * @return the associated album key
      */
    def expectAlbumInTreeModel(artist: String, album: String): AlbumKey =
      val upperArtist = toUpper(artist)
      val upperAlbum = toUpper(album)
      val key = treeModel.getProperty(upperArtist + "|" + upperAlbum)
      key shouldBe a[AlbumKey]
      key.asInstanceOf[AlbumKey]

    /**
      * Checks whether the table model contains all the specified songs.
      *
      * @param songs the songs to be checked
      */
    def expectSongsInTable(songs: Seq[SongData]): Unit =
      songs forall tableModel.contains shouldBe true

    /**
      * Checks whether the enabled state of the action with the given name has
      * been set to the given value.
      *
      * @param name    the name of the action
      * @param enabled the expected enabled state
      * @return this test helper
      */
    def verifyAction(name: String, enabled: Boolean): MediaControllerTestHelper =
      isActionEnabled(name) shouldBe enabled
      this

    /**
      * Sends a playlist changed notification to the test controller with the
      * specified ''playlistClosed'' flag.
      *
      * @param playlistClosed flag whether the playlist is closed
      * @return this test helper
      */
    def changePlaylistOpenState(playlistClosed: Boolean): MediaControllerTestHelper =
      sendPlaylistState(controller, playlistClosed)
      this

    /**
      * Closes the playlist.
      *
      * @return this test helper
      */
    def closePlaylist(): MediaControllerTestHelper =
      changePlaylistOpenState(playlistClosed = true)

    /**
      * Creates a mock for the message bus. All messages published via the bus
      * are stored in an internal buffer.
      *
      * @return the mock message bus
      */
    private def createMessageBusMock(): MessageBus =
      val bus = mock[MessageBus]
      when(bus.publish(any())).thenAnswer((invocationOnMock: InvocationOnMock) => {
        publishedMessages += invocationOnMock.getArguments.head
        null
      })
      bus

    /**
      * Creates a mock for the media facade.
      *
      * @param msgBus the underlying message bus
      * @return the mock for the remote message bus
      */
    private def createMediaFacade(msgBus: MessageBus): MediaFacade =
      val facade = mock[MediaFacade]
      when(facade.bus).thenReturn(msgBus)
      facade

    /**
      * Creates the configuration acting as tree model.
      *
      * @return the tree model configuration
      */
    private def createTreeModelConfig(): HierarchicalConfiguration =
      val config = new HierarchicalConfiguration
      val exprEngine = new DefaultExpressionEngine
      exprEngine setPropertyDelimiter "|"
      config setExpressionEngine exprEngine
      config

    /**
      * Creates a mock tree handler.
      *
      * @param model the model for the tree
      * @return the mock tree handler
      */
    private def createTreeHandler(model: HierarchicalConfiguration): TreeHandler =
      val handler = mock[TreeHandler]
      when(handler.getModel).thenReturn(model)
      handler

    /**
      * Creates a mock table handler.
      *
      * @param model the model for the table
      * @return the mock table handler
      */
    private def createTableHandler(model: java.util.ArrayList[AnyRef]): TableHandler =
      val handler = mock[TableHandler]
      when(handler.getModel).thenReturn(model)
      when(handler.getSelectedIndices).thenReturn(Array.emptyIntArray)
      handler

    /**
      * Initializes mocks for the managed actions and creates a mock action
      * store.
      *
      * @return the mock action store
      */
    private def initActions(): ActionStore =
      createActions(AppendActions: _*)
      createActionStore()

    /**
      * Creates an initialized test controller instance.
      *
      * @return the test controller
      */
    private def createController(): MediaController =
      val ctrl = new MediaController(mediaFacade = mediaFacade, songFactory =
        songFactory, comboMedia = comboHandler, treeHandler = treeHandler, tableHandler =
        tableHandler, inProgressWidget = labelInProgress, undefinedMediumName =
        UndefinedMediumName, actionStore = actionStore)
      sendPlaylistState(ctrl, playlistClosed = false)
      ctrl

