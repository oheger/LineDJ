/*
 * Copyright 2015 The Developers Team.
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

import java.nio.file.Paths
import java.util
import java.util.Locale

import de.oliver_heger.linedj.browser.cache.{MetaDataRegistration, RemoveMetaDataRegistration}
import de.oliver_heger.linedj.client.model.{SongData, SongDataFactory}
import de.oliver_heger.linedj.client.remoting.MessageBus
import de.oliver_heger.linedj.client.remoting.RemoteRelayActor.ServerUnavailable
import de.oliver_heger.linedj.media.{AvailableMedia, MediumID, MediumInfo}
import de.oliver_heger.linedj.metadata.{MediaMetaData, MetaDataChunk}
import net.sf.jguiraffe.gui.builder.components.WidgetHandler
import net.sf.jguiraffe.gui.builder.components.model._
import org.apache.commons.configuration.HierarchicalConfiguration
import org.apache.commons.configuration.tree.{ConfigurationNode, DefaultConfigurationNode, DefaultExpressionEngine}
import org.mockito.Matchers.{any, anyInt}
import org.mockito.Mockito
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag
import scala.util.Random

object MediaControllerSpec {
  /** Constant for a medium name. */
  private val Medium = "Rock1"

  /** Constant for a test medium ID. */
  private val TestMediumID = mediumID(Medium)

  /** A list with the sorted names of available media. */
  private val MediaNames = List(Medium, "Rock2", "Rock3")

  /** The name for the undefined medium. */
  private val UndefinedMediumName = "The undefined medium!"

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

  /**
   * Returns the ID for a test medium based on its name.
   * @param name the name of the medium
   * @return the corresponding test medium ID
   */
  private def mediumID(name: String): MediumID = MediumID("media://" + name,
    Some(Paths.get(name).toString))

  /**
   * Transforms the given string to upper case.
   * @param s the string
   * @return the string in upper case
   */
  private def toUpper(s: String): String = s toUpperCase Locale.ENGLISH

  /**
   * Creates a medium info object with dummy property values for the specified
   * medium ID.
   * @param id the medium ID
   * @return the undefined medium info
   */
  private def undefinedMediumInfo(id: MediumID): MediumInfo =
    MediumInfo(name = "(undefined)", description = null, mediumID = id, orderMode = null,
      orderParams = null, checksum = "nocheck")

  /**
   * Creates a medium info object for a medium without a description file.
   * @param uri the URI of the medium
   * @return the undefined medium info
   */
  private def undefinedMediumInfo(uri: String): MediumInfo =
    undefinedMediumInfo(MediumID(uri, None))

  /**
   * Generates a mapping for a medium info object.
   * @param info the medium info object
   * @return the mapping
   */
  private def infoMapping(info: MediumInfo): (MediumID, MediumInfo) = (info.mediumID, info)

  /**
   * Creates the message with available media based on the list of media
   * names.
   * @return the message for available media
   */
  private def createAvailableMediaMsg(): AvailableMedia = {
    val definedMappings = MediaNames map { m =>
      (mediumID(m), mediumInfo(m))
    }
    val undefinedMappings = List(infoMapping(undefinedMediumInfo("someURI")), infoMapping
      (undefinedMediumInfo("anotherURI")), infoMapping(undefinedMediumInfo(MediumID
      .UndefinedMediumID)))
    val mappings = Random.shuffle(List(definedMappings, undefinedMappings).flatten)
    AvailableMedia(Map(mappings: _*))
  }

  /**
   * Creates a ''MediumInfo'' mock.
   * @param name the medium name
   * @return the mock for this medium info
   */
  private def mediumInfo(name: String): MediumInfo = {
    val info = mock(classOf[MediumInfo])
    when(info.name).thenReturn(name)
    info
  }

  /**
   * Creates ''SongData'' objects for a test album.
   * @param artist the artist
   * @param album the album name
   * @param songs the sequence of songs on this album
   * @param mediumID an optional medium ID
   * @return a corresponding sequence of ''SongData'' objects
   */
  private def createSongData(artist: String, album: String, songs: Seq[String],
                              mediumID: MediumID = TestMediumID): Seq[SongData] = {
    songs.zipWithIndex.map(e => SongData(mediumID, "song://" + album + "/" + e._1, MediaMetaData(title =
      Some(e._1),
      artist = Some(artist), album = Some(album), trackNumber = Some(e._2)), null))
  }

  /**
   * Creates a ''MetaDataChunk'' object from the specified data.
   * @param mediumID the medium URI/ID
   * @param complete the complete flag
   * @param songs a sequence with the songs
   * @return the chunk
   */
  private def createChunk(mediumID: MediumID = TestMediumID, complete: Boolean = false,
                          songs: Seq[SongData]): MetaDataChunk = {
    val mappings = songs map (s => (s.uri, s.metaData))
    MetaDataChunk(mediumID = mediumID, complete = complete, data = Map(mappings: _*))
  }

  /**
   * Creates a ''TreeNodePath'' for the specified configuration node.
   * @param node the configuration node
   * @return the ''TreeNodePath'' for this node
   */
  private def createTreePath(node: ConfigurationNode): TreeNodePath = new TreeNodePath(node)

  /**
   * Creates a ''TreeNodePath'' that points to a node representing the
   * specified album.
   * @param artist the artist
   * @param album the album
   * @return the ''TreeNodePath'' for this album
   */
  private def createTreePath(artist: String, album: String): TreeNodePath = {
    val key = AlbumKey(toUpper(artist), toUpper(album))
    val node = new DefaultConfigurationNode(key.album)
    node setValue key
    createTreePath(node)
  }
}

/**
 * Test class for ''MediaController''.
 */
class MediaControllerSpec extends FlatSpec with Matchers with MockitoSugar {

  import MediaControllerSpec._

  "A MediaController" should "disable the combo when the server is unavailable" in {
    val helper = new MediaControllerTestHelper

    helper send ServerUnavailable
    verify(helper.comboHandler).setEnabled(false)
  }

  it should "pass available media to the combo handler" in {
    val helper = new MediaControllerTestHelper
    helper prepareMediaListModel 3

    helper send AvailableMediaMsg
    val verInOrder = Mockito.inOrder(helper.comboHandler)
    verInOrder.verify(helper.comboHandler).removeItem(2)
    verInOrder.verify(helper.comboHandler).removeItem(1)
    verInOrder.verify(helper.comboHandler).removeItem(0)
    verInOrder.verify(helper.comboHandler).addItem(0, Medium, TestMediumID)
    verInOrder.verify(helper.comboHandler).addItem(1, MediaNames(1), mediumID(MediaNames(1)))
    verInOrder.verify(helper.comboHandler).addItem(2, MediaNames(2), mediumID(MediaNames(2)))
    verInOrder.verify(helper.comboHandler).addItem(3, UndefinedMediumName, MediumID.UndefinedMediumID)
    verInOrder.verify(helper.comboHandler).setEnabled(true)
  }

  it should "add an entry for the undefined medium only if it exists" in {
    val mediaMap = AvailableMediaMsg.media - MediumID.UndefinedMediumID
    val helper = new MediaControllerTestHelper
    helper prepareMediaListModel 0

    helper send AvailableMedia(mediaMap)
    verify(helper.comboHandler, never()).removeItem(anyInt())
    verify(helper.comboHandler, never()).addItem(3, UndefinedMediumName, MediumID.UndefinedMediumID)
  }

  it should "query meta data for a newly selected medium" in {
    val helper = new MediaControllerTestHelper

    helper.selectMedium()
    helper.findMessageType[RemoveMetaDataRegistration] shouldBe 'empty
  }

  it should "remove a previous meta data registration" in {
    val oldMedium = mediumID("someMedium")
    val helper = new MediaControllerTestHelper
    helper selectMedium oldMedium
    helper.clearReceivedMessages()

    helper.selectMedium()
    helper expectMessage RemoveMetaDataRegistration(oldMedium, helper.controller)
  }

  it should "not remove a meta data registration when receiving new media" in {
    val helper = new MediaControllerTestHelper
    helper prepareMediaListModel 0
    helper.selectMedium()
    helper.clearReceivedMessages()

    helper send AvailableMediaMsg
    helper selectMedium mediumID(MediaNames(1))
    helper.findMessageType[RemoveMetaDataRegistration] shouldBe 'empty
  }

  it should "clear the tree model when another medium is selected" in {
    val helper = new MediaControllerTestHelper
    helper.treeModel.setProperty("someKey", "someValue")

    helper selectMedium mediumID(MediaNames(1))
    helper.treeModel should not be 'empty
    helper.clearReceivedMessages()

    helper.selectMedium()
    helper.treeModel shouldBe 'empty
  }

  it should "set the root node of the tree model to the medium name" in {
    val helper = new MediaControllerTestHelper
    helper prepareMediaListModel 0
    helper send AvailableMediaMsg
    helper.selectMedium()

    helper.treeModel.getRootNode.getName should be(Medium)
  }

  it should "use the undefined name for an unknown medium ID" in {
    val helper = new MediaControllerTestHelper
    helper prepareMediaListModel 0
    helper send AvailableMediaMsg
    helper.selectMedium(MediumID("unknown medium", None))

    helper.treeModel.getRootNode.getName should be(UndefinedMediumName)
  }

  it should "use the undefined name for the undefined medium ID" in {
    val helper = new MediaControllerTestHelper
    helper prepareMediaListModel 0
    helper send AvailableMediaMsg
    helper.selectMedium(MediumID.UndefinedMediumID)

    helper.treeModel.getRootNode.getName should be(UndefinedMediumName)
  }

  it should "clear the table model when another medium is selected" in {
    val helper = new MediaControllerTestHelper
    helper.tableModel add "someData"

    helper selectMedium mediumID(MediaNames(1))
    helper.tableModel should not be 'empty
    helper.clearReceivedMessages()

    helper.selectMedium()
    helper.tableModel shouldBe 'empty
    verify(helper.tableHandler).tableDataChanged()
  }

  it should "populate the tree model when meta data arrives" in {
    val helper = new MediaControllerTestHelper
    val chunk = createChunk(songs = createSongData(Artist1, Album1, Songs1))

    helper selectMediumAndSendMeta chunk
    helper.expectAlbumInTreeModel(Artist1, Album1)
  }

  it should "process multiple chunks of data" in {
    val helper = new MediaControllerTestHelper
    val chunk1 = createChunk(songs = createSongData(Artist1, Album1, Songs1))
    val chunk2 = createChunk(songs = createSongData(Artist1, Album2, Songs2))

    val callback = helper selectMediumAndSendMeta chunk1
    callback(chunk2)
    helper.expectAlbumInTreeModel(Artist1, Album1)
    helper.expectAlbumInTreeModel(Artist1, Album2)
  }

  it should "process chunks with multiple artists" in {
    val ArtistPrefix = "TestArtist"
    val AlbumPrefix = "TestAlbum"
    val ArtistCount = 8

    def createSyntheticSongData(artistIndex: Int): Seq[SongData] = {
      createSongData(ArtistPrefix + artistIndex, AlbumPrefix + "1", List("Song" + artistIndex)) ++
        createSongData(ArtistPrefix + artistIndex, AlbumPrefix + "2", List("OtherSong" +
          artistIndex))
    }

    @tailrec
    def appendSyntheticSongData(songs: Seq[SongData], index: Int): Seq[SongData] =
      if (index >= ArtistCount) songs
      else appendSyntheticSongData(songs ++ createSyntheticSongData(index), index + 1)

    val songsOfArtist1 = createSongData(Artist1, Album1, Songs1) ++ createSongData(Artist1,
      Album2, Songs2)
    val songsOfArtist2 = createSongData(Artist2, Album3, Songs3)
    val allSongs = appendSyntheticSongData(songsOfArtist1 ++ songsOfArtist2, 1)
    val chunk = createChunk(songs = allSongs)
    val helper = new MediaControllerTestHelper
    val callback = helper selectMediumAndSendMeta createChunk(songs = Nil)

    callback(chunk)
    helper.expectAlbumInTreeModel(Artist1, Album1)
    helper.expectAlbumInTreeModel(Artist1, Album2)
    helper.expectAlbumInTreeModel(Artist2, Album3)
  }

  it should "ignore a chunk for a medium which is not currently selected" in {
    val helper = new MediaControllerTestHelper
    val callback = helper.selectMedium()

    callback(createChunk(mediumID = mediumID("another medium ID"), songs = createSongData(Artist1, Album1,
      Songs1)))
    helper.treeModel shouldBe 'empty
  }

  it should "ignore meta data chunks if no medium is selected" in {
    val helper = new MediaControllerTestHelper
    helper prepareMediaListModel 0
    val callback = helper.selectMedium()
    helper send AvailableMediaMsg

    callback(createChunk(mediumID = mediumID("other"), songs = createSongData(Artist1, Album1,
      Songs1)))
    helper.treeModel shouldBe 'empty
  }

  it should "fill the table model when an album is selected" in {
    val songs = createSongData(Artist1, Album1, Songs1)
    val chunk = createChunk(songs = songs)
    val helper = new MediaControllerTestHelper
    helper selectMediumAndSendMeta chunk

    helper selectAlbums createTreePath(Artist1, Album1)
    verify(helper.tableHandler).tableDataChanged()
    helper expectSongsInTable songs
  }

  it should "update the table model for multiple chunks" in {
    val songs1 = createSongData(Artist1, Album1, Songs1)
    val songs2 = createSongData(Artist2, Album3, Songs3)
    val chunk1 = createChunk(songs = songs1)
    val chunk2 = createChunk(songs = songs2)
    val helper = new MediaControllerTestHelper
    val callback = helper selectMediumAndSendMeta chunk1
    callback(chunk2)

    helper selectAlbums createTreePath(Artist2, Album3)
    helper expectSongsInTable songs2
  }

  it should "clear the table model when the selection is changed" in {
    val songs = createSongData(Artist1, Album1, Songs1) ++ createSongData(Artist2, Album3, Songs3)
    val helper = new MediaControllerTestHelper
    helper selectMediumAndSendMeta createChunk(songs = songs)

    helper selectAlbums createTreePath(Artist1, Album1)
    helper selectAlbums createTreePath(Artist2, Album3)
    helper.tableModel.size() should be(Songs3.size)
  }

  it should "ignore an album selection if no model is initialized" in {
    val helper = new MediaControllerTestHelper
    helper.selectMedium()

    helper selectAlbums createTreePath(Artist1, Album1)
    helper.tableModel shouldBe 'empty
  }

  it should "support multiple selected album paths" in {
    val songs1 = createSongData(Artist1, Album1, Songs1)
    val songs2 = createSongData(Artist1, Album2, Songs2)
    val songs3 = createSongData(Artist2, Album3, Songs3)
    val chunk = createChunk(songs = songs1 ++ songs2 ++ songs3)
    val helper = new MediaControllerTestHelper
    helper selectMediumAndSendMeta chunk

    helper.selectAlbums(createTreePath(Artist1, Album1),
      createTreePath(new DefaultConfigurationNode),
      createTreePath(Artist1, Album2),
      createTreePath(new DefaultConfigurationNode))
    helper expectSongsInTable songs1
    helper expectSongsInTable songs2
    verify(helper.tableHandler).tableDataChanged()
  }

  it should "update the table model if it is affected by a received chunk" in {
    val helper = new MediaControllerTestHelper
    val callback = helper selectMediumAndSendMeta createChunk(songs =
      createSongData(Artist1, Album1, Songs1 take 1))
    helper selectAlbums createTreePath(Artist1, Album1)
    verify(helper.tableHandler).tableDataChanged()

    callback(createChunk(songs = createSongData(Artist1, Album1, Songs1 drop 1)))
    verify(helper.tableHandler, times(2)).tableDataChanged()
    helper.tableModel.size() should be(Songs1.size)
  }

  it should "reset all models when the medium selection changes" in {
    val OtherMedium = mediumID("_other")
    val songs1 = createSongData(Artist1, Album1, Songs1)
    val songs2 = createSongData(Artist1, Album2, Songs2 take 1)
    val songs3 = createSongData(Artist1, Album2, Songs2 drop 1, mediumID = OtherMedium)
    val helper = new MediaControllerTestHelper
    helper selectMediumAndSendMeta createChunk(songs = songs1 ++ songs2)
    helper selectAlbums createTreePath(Artist1, Album2)
    helper.clearReceivedMessages()

    helper selectMediumAndSendMeta createChunk(mediumID = OtherMedium, songs = songs3)
    helper selectAlbums createTreePath(Artist1, Album2)
    helper.expectAlbumInTreeModel(Artist1, Album2)
    helper expectSongsInTable songs3
    val artistNode = helper.treeModel.getRootNode.getChild(0)
    artistNode.getChildrenCount should be(1)
    helper.tableModel.size() should be(songs3.size)
    verify(helper.tableHandler, times(3)).tableDataChanged()
  }

  it should "display the in-progress indicator after a medium selection" in {
    val helper = new MediaControllerTestHelper
    helper.selectMedium()

    verify(helper.labelInProgress).setVisible(true)
  }

  it should "hide the in-progress indicator when the last data chunk was received" in {
    val chunk1 = createChunk(songs = createSongData(Artist1, Album1, Songs1))
    val chunk2 = createChunk(songs = createSongData(Artist2, Album3, Songs3), complete = true)
    val helper = new MediaControllerTestHelper
    val callback = helper selectMediumAndSendMeta chunk1

    verify(helper.labelInProgress, never()).setVisible(false)
    callback(chunk2)
    verify(helper.labelInProgress).setVisible(false)
  }

  /**
   * Adds all test songs to the controller.
   * @param helper the helper
   * @return the list of songs that have been added
   */
  private def addAllSongsToController(helper: MediaControllerTestHelper): List[Seq[SongData]] = {
    val songsAlbum1 = createSongData(Artist1, Album1, Songs1)
    val songsAlbum2 = createSongData(Artist1, Album2, Songs2)
    val songsAlbum3 = createSongData(Artist2, Album3, Songs3)
    val songs = songsAlbum1 ++ songsAlbum2 ++ songsAlbum3
    helper selectMediumAndSendMeta createChunk(songs = songs)
    List(songsAlbum2, songsAlbum1, songsAlbum3)
  }

  it should "return the songs of the currently selected albums" in {
    val helper = new MediaControllerTestHelper
    val songs = addAllSongsToController(helper)
    helper.selectAlbums(createTreePath(Artist2, Album3), createTreePath(Artist1, Album1))

    helper.controller.songsForSelectedAlbums should be(songs(1) ++ songs(2))
  }

  it should "ignore a request for songs if there is no model available" in {
    val helper = new MediaControllerTestHelper

    helper.controller.songsForSelectedAlbums should have size 0
  }

  it should "return the songs of the currently selected artists" in {
    val helper = new MediaControllerTestHelper
    val songs = addAllSongsToController(helper)
    helper.selectAlbums(createTreePath(Artist2, Album3),
      createTreePath(helper.treeModel.getRootNode.getChild(0)))

    helper.controller.songsForSelectedArtists should be(songs.flatten)
  }

  it should "return the songs of the current medium" in {
    val helper = new MediaControllerTestHelper
    val songs = addAllSongsToController(helper)

    helper.controller.songsForSelectedMedium should be(songs.flatten)
  }

  /**
   * A test helper class managing mock objects for the dependencies of a
   * controller.
   */
  private class MediaControllerTestHelper {
    val songFactory = new SongDataFactory(null) {
      override def createSongData(mediumID: MediumID, uri: String, metaData: MediaMetaData): SongData =
        SongData(mediumID, uri, metaData, null)
    }

    /** A mock for the message bus. */
    val messageBus = createMessageBusMock()

    /** The mock for the combo handler. */
    val comboHandler = mock[ListComponentHandler]

    /** The configuration acting as model for the tree view. */
    val treeModel = createTreeModelConfig()

    /** The handler for the tree view. */
    val treeHandler = createTreeHandler(treeModel)

    /** The model for the table control. */
    val tableModel = new util.ArrayList[AnyRef]

    /** The handler for the table. */
    val tableHandler = createTableHandler(tableModel)

    /** The in-progress widget. */
    val labelInProgress = mock[WidgetHandler]

    /** The controller test instance. */
    val controller = new MediaController(messageBus = messageBus, songFactory = songFactory,
      comboMedia = comboHandler, treeHandler = treeHandler, tableHandler = tableHandler,
      inProgressWidget = labelInProgress, undefinedMediumName = UndefinedMediumName)

    /** Stores the messages published to the message bus. */
    private val publishedMessages = ListBuffer.empty[Any]

    /**
     * Sends the specified message to the receive method of the test
     * controller.
     * @param msg the message
     */
    def send(msg: Any): Unit = {
      controller.receive(msg)
    }

    /**
     * Clears the buffer with the messages published to the message bus.
     */
    def clearReceivedMessages(): Unit = {
      publishedMessages.clear()
    }

    /**
     * Verifies that the controller has registered for meta data of the
     * specified medium.
     * @param mediumID the medium ID
     * @return the function for receiving meta data chunks
     */
    def verifyMetaDataRequest(mediumID: MediumID = TestMediumID): MetaDataChunk => Unit = {
      val regMsg = expectMessageType[MetaDataRegistration]
      regMsg.listenerID should be(controller)
      regMsg.mediumID should be(mediumID)
      regMsg.listenerCallback
    }

    /**
     * Creates a mock for a list model and installs it for the combo box.
     * @param size the size to be returned by the list model
     * @return the list model mock
     */
    def prepareMediaListModel(size: Int): ListModel = {
      val model = mock[ListModel]
      when(model.size()).thenReturn(size)
      when(comboHandler.getListModel).thenReturn(model)
      model
    }

    /**
     * Selects the specified medium in the controller and verifies that the
     * expected actions are performed. The controller should request the meta
     * data of this medium. The corresponding listener callback is returned.
     * @param mediumID the medium ID
     * @return the function for receiving meta data chunks
     */
    def selectMedium(mediumID: MediumID = TestMediumID): MetaDataChunk => Unit = {
      controller selectMedium mediumID
      verifyMetaDataRequest(mediumID)
    }

    /**
     * Convenience method for selecting a medium and sending a chunk of meta
     * data for it. The meta data callback is returned.
     * @param chunk the chunk of meta data
     * @return the function for receiving meta data
     */
    def selectMediumAndSendMeta(chunk: MetaDataChunk): MetaDataChunk => Unit = {
      val callback = selectMedium(chunk.mediumID)
      clearReceivedMessages()
      callback(chunk)
      callback
    }

    /**
     * Selects the specified tree paths (representing artists or albums).
     * @param paths the paths to be selected
     */
    def selectAlbums(paths: TreeNodePath*): Unit = {
      controller selectAlbums paths.toArray
    }

    /**
     * Searches for a message of the given type and returns an option with the
     * found instance.
     * @param t the class tag
     * @tparam T the type of the message
     * @return an options with the found message instance
     */
    def findMessageType[T](implicit t: ClassTag[T]): Option[T] = {
      val cls = t.runtimeClass
      publishedMessages.toList.find(cls.isInstance).map(_.asInstanceOf[T])
    }

    /**
     * Expects that a message of the given type was published via the message
     * bus and returns the first occurrence.
     * @param t the class tag
     * @tparam T the type of the desired message
     * @return the message of this type
     */
    def expectMessageType[T](implicit t: ClassTag[T]): T = {
      val optMsg = findMessageType(t)
      optMsg shouldBe 'defined
      optMsg.get
    }

    /**
     * Expects that the specified message was published via the message bus.
     * @param msg the expected message
     * @tparam T the type of the message
     * @return the same message
     */
    def expectMessage[T](msg: T): T = {
      publishedMessages should contain(msg)
      msg
    }

    /**
     * Checks whether a specific album is stored in the tree model.
     * @param artist the artist
     * @param album the album
     * @return the associated album key
     */
    def expectAlbumInTreeModel(artist: String, album: String): AlbumKey = {
      val upperArtist = toUpper(artist)
      val upperAlbum = toUpper(album)
      val key = treeModel.getProperty(upperArtist + "|" + upperAlbum)
      key shouldBe a[AlbumKey]
      key.asInstanceOf[AlbumKey]
    }

    /**
     * Checks whether the table model contains all the specified songs.
     * @param songs the songs to be checked
     */
    def expectSongsInTable(songs: Seq[SongData]): Unit = {
      songs forall tableModel.contains shouldBe true
    }

    /**
     * Creates a mock for the message bus. All messages published via the bus
     * are stored in an internal buffer.
     * @return the mock message bus
     */
    private def createMessageBusMock(): MessageBus = {
      val bus = mock[MessageBus]
      when(bus.publish(any())).thenAnswer(new Answer[Any] {
        override def answer(invocationOnMock: InvocationOnMock): Any = {
          publishedMessages += invocationOnMock.getArguments.head
          null
        }
      })
      bus
    }

    /**
     * Creates the configuration acting as tree model.
     * @return the tree model configuration
     */
    private def createTreeModelConfig(): HierarchicalConfiguration = {
      val config = new HierarchicalConfiguration
      val exprEngine = new DefaultExpressionEngine
      exprEngine setPropertyDelimiter "|"
      config setExpressionEngine exprEngine
      config
    }

    /**
     * Creates a mock tree handler.
     * @param model the model for the tree
     * @return the mock tree handler
     */
    private def createTreeHandler(model: HierarchicalConfiguration): TreeHandler = {
      val handler = mock[TreeHandler]
      when(handler.getModel).thenReturn(model)
      handler
    }

    /**
     * Creates a mock table handler.
     * @param model the model for the table
     * @return the mock table handler
     */
    private def createTableHandler(model: java.util.ArrayList[AnyRef]): TableHandler = {
      val handler = mock[TableHandler]
      when(handler.getModel).thenReturn(model)
      handler
    }
  }

}
