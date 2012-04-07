package de.oliver_heger.splaya.playlist.impl

import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar
import de.oliver_heger.splaya.playlist.PlaylistFileStore
import de.oliver_heger.splaya.playlist.FSScanner
import de.oliver_heger.splaya.playlist.PlaylistGenerator
import de.oliver_heger.splaya.engine.QueuingActor
import org.junit.Before
import org.junit.After
import de.oliver_heger.splaya.engine.Exit
import de.oliver_heger.splaya.engine.WaitForExit
import org.junit.Test
import org.easymock.EasyMock
import de.oliver_heger.splaya.engine.AddSourceStream
import scala.xml.Elem

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
    for (i <- startIdx to PlaylistSize) yield playlistURI(i)

  /**
   * Checks whether the correct playlist was sent to the source reader actor.
   * @param startIdx the index of the first URI in the playlist
   * @param skipPos the skip position of the first playlist item
   * @param skipTime the skip time of the first playlist item
   */
  private def checkSentPlaylist(startIdx: Int, skipPos: Long, skipTime: Long) {
    sourceActor.expectMessage(AddSourceStream(playlistURI(startIdx), startIdx,
      skipPos, skipTime))
    for (i <- startIdx + 1 to PlaylistSize) {
      sourceActor.expectMessage(AddSourceStream(playlistURI(i), i, 0, 0))
    }
    sourceActor.ensureNoMessages()
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
   * Tests whether an already existing playlist is detected if a medium is read.
   */
  @Test def testReadMediumExistingPlaylist() {
    val currentIndex = 8
    val pl = createPlaylist()
    val playlistData = <configuration>
                         <current>
                           <index>{ currentIndex }</index>
                           <position>{ CurrentPos }</position>
                           <time>{ CurrentTime }</time>
                         </current>
                         <list>
                           { for (uri <- pl) yield <file name={ uri }/> }
                         </list>
                       </configuration>
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
}
