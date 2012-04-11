package de.oliver_heger.splaya.playlist.impl

import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar
import de.oliver_heger.splaya.playlist.PlaylistFileStore
import de.oliver_heger.splaya.playlist.FSScanner
import de.oliver_heger.splaya.playlist.PlaylistGenerator
import de.oliver_heger.splaya.tsthlp.QueuingActor
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.After
import de.oliver_heger.splaya.engine.msg.Exit
import org.junit.Test
import org.easymock.EasyMock
import de.oliver_heger.splaya.engine.msg.AddSourceStream
import scala.xml.Elem
import de.oliver_heger.splaya.AudioSource
import de.oliver_heger.splaya.PlaybackSourceStart
import de.oliver_heger.splaya.PlaybackPositionChanged
import de.oliver_heger.splaya.PlaybackTimeChanged
import de.oliver_heger.splaya.PlaybackSourceEnd
import de.oliver_heger.splaya.engine.msg.Gateway
import de.oliver_heger.splaya.tsthlp.WaitForExit

/**
 * Test class for ''PlaylistCtrlActor''.
 */
class TestPlaylistCtrlActor extends JUnitSuite with EasyMockSugar {
  /** Constant for a playlist identifier. */
  private val PlaylistID = "ATestPlaylist"

  /** Constant for a URI prefix used in a test playlist. */
  private val URIPrefix = "test://TestAudioSource"

  /** Constant for the test medium URI. */
  private val RootURI = "file:///C:/music"

  /** Constant for a test playlist name. */
  private val PlaylistName = "My special test playlist"

  /** Constant for a test playlist description. */
  private val PlaylistDesc = "A test playlist with test songs."

  /** Constant for a special ordering mode used for the test playlist. */
  private val OrderMode = "SpecialTestOrdering"

  /** Constant for a skip position in a persistent playlist. */
  private val CurrentPos = 10000L

  /** Constant for a skip time in a persistent playlist. */
  private val CurrentTime = 60000L

  /** Constant for the number of URIs in the test playlist. */
  private val PlaylistSize = 32

  /** A mock for the FS scanner. */
  private var scanner: FSScanner = _

  /** A mock for the playlist store. */
  private var store: PlaylistFileStore = _

  /** A mock for the playlist generator. */
  private var generator: PlaylistGenerator = _

  /** A mock source actor. */
  private var sourceActor: QueuingActor = _

  /** The actor to be tested. */
  private var actor: PlaylistCtrlActor = _

  @Before def setUp() {
    scanner = mock[FSScanner]
    store = mock[PlaylistFileStore]
    generator = mock[PlaylistGenerator]
    sourceActor = new QueuingActor
    sourceActor.start()
    actor = new PlaylistCtrlActor(sourceActor, scanner, store, generator)
    actor.start()
  }

  @After def tearDown() {
    sourceActor.shutdown()
    if (actor != null) {
      shutdownActor()
    }
  }

  /**
   * Shuts down the test actor.
   */
  private def shutdownActor() {
    val msg = new WaitForExit
    if (!msg.shutdownActor(actor)) {
      fail("Actor did not exit!")
    }
    actor = null
  }

  /**
   * Creates a URI for a playlist item.
   * @param idx the index of the item
   * @return the URI for this playlist item
   */
  private def playlistURI(idx: Int) = URIPrefix + idx

  /**
   * Creates a test playlist.
   * @param startIdx the index of the first URI in the test playlist
   * @return a sequence with the URIs in the test playlist
   */
  private def createPlaylist(startIdx: Int = 0): Seq[String] =
    for (i <- startIdx until PlaylistSize) yield playlistURI(i)

  /**
   * Checks whether the correct playlist was sent to the source reader actor.
   * @param startIdx the index of the first URI in the playlist
   * @param skipPos the skip position of the first playlist item
   * @param skipTime the skip time of the first playlist item
   * @param checkNoMsgs if '''true''', checks whether the line actor did not
   * receive any more messages
   */
  private def checkSentPlaylist(startIdx: Int, skipPos: Long, skipTime: Long,
    checkNoMsgs: Boolean = true) {
    sourceActor.expectMessage(AddSourceStream(playlistURI(startIdx), startIdx,
      skipPos, skipTime))
    for (i <- startIdx + 1 until PlaylistSize) {
      sourceActor.expectMessage(AddSourceStream(playlistURI(i), i, 0, 0))
    }
    if (checkNoMsgs) {
      sourceActor.ensureNoMessages()
    }
  }

  /**
   * Helper method for preparing the mock objects to expect a scan operation
   * and access to the playlist file store for querying the persistent playlist
   * and the settings.
   * @param list the playlist
   * @param playlistData the persistent playlist data to be returned
   * @param settingsData the settings data to be returned
   */
  private def expectPlaylistProcessing(list: Seq[String],
    playlistData: Option[Elem], settingsData: Option[Elem]) {
    EasyMock.expect(scanner.scan(RootURI)).andReturn(list)
    EasyMock.expect(store.calculatePlaylistID(list)).andReturn(PlaylistID)
    EasyMock.expect(store.loadPlaylist(PlaylistID)).andReturn(playlistData)
    EasyMock.expect(store.loadSettings(PlaylistID)).andReturn(settingsData)
  }

  /**
   * Generates a XML fragment with test playlist settings.
   * @return the fragment
   */
  private def createSettings() =
    <configuration>
      <name>{ PlaylistName }</name>
      <description>{ PlaylistDesc }</description>
      <order>
        <mode>{ OrderMode }</mode>
        <params>
          <coolness>true</coolness>
        </params>
      </order>
    </configuration>

  /**
   * Generates a XML document for a persistent playlist. The current section
   * contains the specified index and the default skip positions. The list
   * section lists the whole playlist.
   * @param pl the current playlist
   * @param currentIndex the current index in the playlist
   * @param pos the skip position
   * @param time the skip time
   * @return the XML document
   */
  private def createPersistentPlaylist(pl: Seq[String], currentIndex: Int,
    pos: Long = CurrentPos, time: Long = CurrentTime) =
    <configuration>
      <current>
        <index>{ currentIndex }</index>
        <position>{ pos }</position>
        <time>{ time }</time>
      </current>
      <list>
        { for (uri <- pl) yield <file name={ uri }/> }
      </list>
    </configuration>

  /**
   * Tests whether an already existing playlist is detected if a medium is read.
   */
  @Test def testReadMediumExistingPlaylist() {
    val currentIndex = 8
    val pl = createPlaylist()
    val playlistData = createPersistentPlaylist(pl, currentIndex)
    expectPlaylistProcessing(pl, Some(playlistData), None)
    whenExecuting(scanner, store, generator) {
      actor ! ReadMedium(RootURI)
      checkSentPlaylist(currentIndex, CurrentPos, CurrentTime)
      shutdownActor()
    }
  }

  /**
   * Tests whether an already existing playlist file in legacy format is
   * detected if a medium is read.
   */
  @Test def testReadMediumExistingPlaylistLegacy() {
    val pl = createPlaylist()
    val playlistData = <configuration>
                         <current>
                           <position>{ CurrentPos }</position>
                           <time>{ CurrentTime }</time>
                           <file name={ RootURI }/>
                         </current>
                       </configuration>
    expectPlaylistProcessing(pl, Some(playlistData), None)
    whenExecuting(scanner, store, generator) {
      actor ! ReadMedium(RootURI)
      sourceActor.expectMessage(AddSourceStream(RootURI, 0, CurrentPos, CurrentTime))
    }
  }

  /**
   * Tests a read medium operation if there is an existing playlist, but no
   * current index is specified.
   */
  @Test def testReadMediumExistingPlaylistNoIndex() {
    val pl = createPlaylist()
    val playlistData = <configuration>
                         <list>
                           { for (uri <- pl) yield <file name={ uri }/> }
                         </list>
                       </configuration>
    expectPlaylistProcessing(pl, Some(playlistData), None)
    whenExecuting(scanner, store, generator) {
      actor ! ReadMedium(RootURI)
      checkSentPlaylist(0, 0, 0)
    }
  }

  /**
   * Tests a read medium operation if there is an existing playlist file with no
   * content.
   */
  @Test def testReadMediumExistingPlaylistEmpty() {
    val generatedPL = createPlaylist()
    val scannedPL = generatedPL.reverse
    val playlistData = <configuration>
                       </configuration>
    expectPlaylistProcessing(scannedPL, Some(playlistData), None)
    EasyMock.expect(generator.generatePlaylist(scannedPL, "", xml.NodeSeq.Empty))
      .andReturn(generatedPL)
    whenExecuting(scanner, store, generator) {
      actor ! ReadMedium(RootURI)
      checkSentPlaylist(0, 0, 0)
    }
  }

  /**
   * Tests a read medium operation if there is no persistent playlist, but
   * settings are available.
   */
  @Test def testReadMediumNoPlaylistWithSettings() {
    val generatedPL = createPlaylist()
    val scannedPL = generatedPL.reverse
    val settingsData = createSettings()
    expectPlaylistProcessing(scannedPL, None, Some(settingsData))
    EasyMock.expect(generator.generatePlaylist(scannedPL, OrderMode,
      settingsData \\ "params")).andReturn(generatedPL)
    whenExecuting(scanner, store, generator) {
      actor ! ReadMedium(RootURI)
      checkSentPlaylist(0, 0, 0)
    }
  }

  /**
   * Tests whether an empty playlist can be handled correctly.
   */
  @Test def testReadMediumEmptyPlaylist() {
    expectPlaylistProcessing(List.empty, None, None)
    EasyMock.expect(generator.generatePlaylist(List.empty, "", xml.NodeSeq.Empty))
      .andReturn(List.empty)
    whenExecuting(scanner, store, generator) {
      actor ! ReadMedium(RootURI)
      shutdownActor()
      sourceActor.ensureNoMessages()
    }
  }

  /**
   * Prepares a test which requires an existing playlist. The mocks are
   * initialized to create a new playlist which is sent initially to the source
   * actor. Current index is 0.
   */
  private def prepareTestWithPlaylist() {
    val pl = createPlaylist()
    val settingsData = createSettings()
    expectPlaylistProcessing(pl, None, Some(settingsData))
    EasyMock.expect(generator.generatePlaylist(pl, OrderMode,
      settingsData \\ "params")).andReturn(pl)
  }

  /**
   * Tests whether the actor can move to a valid index in the playlist.
   */
  @Test def testMoveToValidIndex() {
    prepareTestWithPlaylist()
    val newIndex = 12
    whenExecuting(scanner, store, generator) {
      actor ! ReadMedium(RootURI)
      actor ! MoveTo(newIndex)
      sourceActor.skipMessages(PlaylistSize)
      checkSentPlaylist(newIndex, 0, 0)
    }
  }

  /**
   * Helper method for testing whether the actor can deal with MoveTo messages
   * with an invalid index.
   * @param idx the index of the message
   */
  private def checkMoveToInvalidIndex(idx: Int) {
    prepareTestWithPlaylist()
    whenExecuting(scanner, store, generator) {
      actor ! ReadMedium(RootURI)
      actor ! MoveTo(idx)
      shutdownActor()
      sourceActor.ensureNoMessages(PlaylistSize)
    }
  }

  /**
   * Tries to move to a playlist index less than 0.
   */
  @Test def testMoveToIndexTooSmall() {
    checkMoveToInvalidIndex(-1)
  }

  /**
   * Tries to move to a playlist index which is too big.
   */
  @Test def testMoveToIndexTooBig() {
    checkMoveToInvalidIndex(PlaylistSize)
  }

  /**
   * Tests whether a message for a relative move is correctly processed.
   */
  @Test def testMoveRelativeValidIndex() {
    prepareTestWithPlaylist()
    whenExecuting(scanner, store, generator) {
      actor ! ReadMedium(RootURI)
      actor ! MoveRelative(5)
      actor ! MoveRelative(-2)
      sourceActor.skipMessages(PlaylistSize)
      checkSentPlaylist(5, 0, 0, false)
      checkSentPlaylist(3, 0, 0)
    }
  }

  /**
   * Tests a relative move which would cause an invalid index.
   */
  @Test def testMoveRelativeInvalidIndex() {
    prepareTestWithPlaylist()
    whenExecuting(scanner, store, generator) {
      actor ! ReadMedium(RootURI)
      actor ! MoveRelative(PlaylistSize - 1)
      actor ! MoveRelative(5)
      sourceActor.skipMessages(PlaylistSize)
      checkSentPlaylist(PlaylistSize - 1, 0, 0, false)
      checkSentPlaylist(PlaylistSize - 1, 0, 0)
    }
  }

  /**
   * Tests a relative move operation if the current playlist is empty.
   */
  @Test def testMoveRelativeNoPlaylist() {
    whenExecuting(scanner, store, generator) {
      actor ! MoveRelative(5)
      shutdownActor()
      sourceActor.ensureNoMessages()
    }
  }

  /**
   * Creates a test audio source with the specified index.
   * @param idx the index
   * @return the test audio source
   */
  private def createSource(idx: Int): AudioSource =
    AudioSource(playlistURI(idx), idx, CurrentPos + idx, 0, 0)

  /**
   * Tests whether the state of the playlist can be saved.
   */
  @Test def testSavePlaylist() {
    prepareTestWithPlaylist()
    val currentIndex = 16
    val pl = createPlaylist()
    store.savePlaylist(PlaylistID, createPersistentPlaylist(pl, currentIndex))
    val source = createSource(currentIndex)
    whenExecuting(scanner, store, generator) {
      actor ! ReadMedium(RootURI)
      actor ! PlaybackSourceStart(source)
      actor ! PlaybackPositionChanged(CurrentPos, 2000, 500, source)
      actor ! PlaybackTimeChanged(CurrentTime)
      actor ! SavePlaylist
      checkSentPlaylist(0, 0, 0)
      shutdownActor()
    }
  }

  /**
   * Tests whether the playlist state is saved automatically after a number of
   * sources has been played.
   */
  @Test def testAutoSavePlaylist() {
    def sendSourceMessages(idx: Int) {
      val source = createSource(idx)
      actor ! PlaybackSourceStart(source)
      actor ! PlaybackSourceEnd(source)
    }

    prepareTestWithPlaylist()
    store.savePlaylist(PlaylistID, createPersistentPlaylist(createPlaylist(),
      2, 0, 0))
    whenExecuting(scanner, store, generator) {
      actor ! ReadMedium(RootURI)
      sendSourceMessages(0)
      sendSourceMessages(1)
      val source = createSource(2)
      actor ! PlaybackSourceStart(source)
      actor ! PlaybackPositionChanged(CurrentPos, 2000, 500, source)
      actor ! PlaybackTimeChanged(CurrentTime)
      actor ! PlaybackSourceEnd(source)
      shutdownActor()
    }
  }

  /**
   * Tests whether a playlist can be persisted if it has been fully played.
   */
  @Test def testSavePlaylistComplete() {
    prepareTestWithPlaylist()
    val source = createSource(PlaylistSize - 1)
    store.savePlaylist(PlaylistID, PlaylistCtrlActor.EmptyPlaylist)
    whenExecuting(scanner, store, generator) {
      actor ! ReadMedium(RootURI)
      actor ! PlaybackSourceStart(source)
      actor ! PlaybackSourceEnd(source)
      actor ! SavePlaylist
      shutdownActor()
    }
  }

  /**
   * Tests whether a newly created playlist is sent around as an event.
   */
  @Test def testPlaylistCreatedEvent() {
    prepareTestWithPlaylist()
    val listener = new QueuingActor
    listener.start()
    Gateway.start()
    Gateway.register(listener)
    whenExecuting(scanner, store, generator) {
      actor ! ReadMedium(RootURI)
      shutdownActor()
    }
    listener.nextMessage() match {
      case pl: PlaylistDataImpl =>
        assert(PlaylistSize === pl.size)
        assert(0 === pl.startIndex)
        val settings = pl.settings
        assert(PlaylistName === settings.name)
        assert(PlaylistDesc === settings.description)
        for (i <- 0 until PlaylistSize) {
          assert(playlistURI(i) === pl.getURI(i))
          val srcData = pl.getAudioSourceData(i)
          assert(playlistURI(i) === srcData.title)
          assertNull("Got an artist", srcData.artistName)
        }
      case _ => fail("Unexpected message!")
    }
    Gateway.unregister(listener)
  }
}
