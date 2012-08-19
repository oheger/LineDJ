package de.oliver_heger.splaya.playlist.impl

import scala.xml.Elem

import org.easymock.EasyMock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar

import de.oliver_heger.splaya.engine.msg.ActorExited
import de.oliver_heger.splaya.engine.msg.AddSourceStream
import de.oliver_heger.splaya.engine.msg.Gateway
import de.oliver_heger.splaya.fs.FSService
import de.oliver_heger.splaya.osgiutil.ServiceWrapper
import de.oliver_heger.splaya.playlist.PlaylistFileStore
import de.oliver_heger.splaya.AudioSource
import de.oliver_heger.splaya.PlaybackPositionChanged
import de.oliver_heger.splaya.PlaybackSourceEnd
import de.oliver_heger.splaya.PlaybackSourceStart
import de.oliver_heger.splaya.PlaybackTimeChanged
import de.oliver_heger.splaya.PlaylistEnd
import de.oliver_heger.splaya.PlaylistSettings
import de.oliver_heger.tsthlp.QueuingActor
import de.oliver_heger.tsthlp.TestActorSupport

/**
 * Test class for ''PlaylistCtrlActor''.
 */
class TestPlaylistCtrlActor extends JUnitSuite with EasyMockSugar
  with TestActorSupport {
  /** The actor type to be tested. */
  type ActorUnderTest = PlaylistCtrlActor

  /** Constant for the set with file extensions. */
  private val Extensions = Set("mp3", "wav")

  /** Constant for a playlist identifier. */
  private val PlaylistID = "ATestPlaylist"

  /** Constant for a URI prefix used in a test playlist. */
  private val URIPrefix = "TestAudioSource"

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

  /** The gateway object. */
  private var gateway: Gateway = _

  /** A mock for the FS service. */
  private var scanner: FSService = _

  /** A mock for the playlist store. */
  private var store: PlaylistFileStore = _

  /** The actor responsible for playlist creation. */
  private var playlistCreationActor: QueuingActor = _

  /** A mock source actor. */
  private var sourceActor: QueuingActor = _

  /** The actor to be tested. */
  protected var actor: ActorUnderTest = _

  @Before def setUp() {
    gateway = new Gateway
    gateway.start()
    scanner = mock[FSService]
    store = mock[PlaylistFileStore]
    playlistCreationActor = new QueuingActor
    playlistCreationActor.start()
    sourceActor = new QueuingActor
    sourceActor.start()
    val wrapper = new ServiceWrapper[FSService]
    wrapper bind scanner
    actor = new PlaylistCtrlActor(gateway, sourceActor, wrapper, store,
      playlistCreationActor, Extensions)
    actor.start()
  }

  @After override def tearDown() {
    sourceActor.shutdown()
    playlistCreationActor.shutdown()
    super.tearDown()
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
    sourceActor.expectMessage(AddSourceStream(RootURI, playlistURI(startIdx),
      startIdx, skipPos, skipTime))
    for (i <- startIdx + 1 until PlaylistSize) {
      sourceActor.expectMessage(AddSourceStream(RootURI, playlistURI(i), i, 0, 0))
    }
    sourceActor.expectMessage(PlaylistEnd)
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
    EasyMock.expect(scanner.scan(RootURI, Extensions)).andReturn(list)
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
          { createOrderParams() }
        </params>
      </order>
    </configuration>

  /**
   * Generates a XML fragment with parameters for the ordering.
   * @return the fragment
   */
  private def createOrderParams() =
    <coolness>true</coolness>

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
        {
          for (uri <- pl) yield <file name={ uri }>
                                      </file>
        }
      </list>
    </configuration>

  /**
   * Creates a mock with playlist settings.
   * @return the playlist settings mock
   */
  private def createPlaylistSettings(): PlaylistSettings = {
    val settings = niceMock[PlaylistSettings]
    EasyMock.expect(settings.mediumURI).andReturn(RootURI).anyTimes()
    EasyMock.replay(settings)
    settings
  }

  /**
   * Tests whether an already existing playlist is detected if a medium is read.
   */
  @Test def testReadMediumExistingPlaylist() {
    val currentIndex = 8
    val pl = createPlaylist()
    val playlistData = createPersistentPlaylist(pl, currentIndex)
    expectPlaylistProcessing(pl, Some(playlistData), None)
    whenExecuting(scanner, store) {
      actor ! ReadMedium(RootURI)
      checkSentPlaylist(currentIndex, CurrentPos, CurrentTime)
      shutdownActor()
    }
    playlistCreationActor.ensureNoMessages()
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
                           <file name={ URIPrefix }/>
                         </current>
                       </configuration>
    expectPlaylistProcessing(pl, Some(playlistData), None)
    whenExecuting(scanner, store) {
      actor ! ReadMedium(RootURI)
      sourceActor.expectMessage(AddSourceStream(RootURI, URIPrefix, 0,
        CurrentPos, CurrentTime))
    }
    playlistCreationActor.ensureNoMessages()
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
    whenExecuting(scanner, store) {
      actor ! ReadMedium(RootURI)
      checkSentPlaylist(0, 0, 0)
    }
    playlistCreationActor.ensureNoMessages()
  }

  /**
   * Extracts a request for generating a playlist from the playlist creation
   * actor. This method fails if no such request is found.
   * @return the extracted request
   */
  private def extractGeneratePlaylistRequest(): GeneratePlaylist =
    playlistCreationActor.nextMessage() match {
      case req: GeneratePlaylist => req
      case other => fail("Unexpected message: " + other)
    }

  /**
   * Extracts the next request for generating a playlist and checks its
   * properties.
   * @param songs the expected list of songs
   * @param mode the expected mode string
   * @param params the expected parameters
   */
  private def checkGeneratePlaylistRequest(songs: Seq[String], mode: String,
    params: xml.NodeSeq = xml.NodeSeq.Empty) {
    val req = extractGeneratePlaylistRequest()
    assertEquals("Wrong list of songs", songs, req.songs)
    assertEquals("Wrong mode", mode, req.settings.orderMode)
    assertEquals("Wrong number of param nodes", params.size,
      req.settings.orderParams.size)
    assertTrue("Wrong parameters: " + req.settings.orderParams,
      req.settings.orderParams containsSlice params)
    assertEquals("Wrong sender", actor, req.sender)
    playlistCreationActor.ensureNoMessages()
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
    whenExecuting(scanner, store) {
      actor ! ReadMedium(RootURI)
      checkGeneratePlaylistRequest(scannedPL, "")
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
    whenExecuting(scanner, store) {
      actor ! ReadMedium(RootURI)
      checkGeneratePlaylistRequest(scannedPL, OrderMode, createOrderParams())
    }
  }

  /**
   * Tests whether an empty playlist can be handled correctly.
   */
  @Test def testReadMediumEmptyPlaylist() {
    expectPlaylistProcessing(List.empty, None, None)
    whenExecuting(scanner, store) {
      actor ! ReadMedium(RootURI)
      checkGeneratePlaylistRequest(List.empty, "")
    }
  }

  /**
   * Tests whether a response for a newly created playlist can be processed.
   */
  @Test def testPlaylistCreated() {
    actor ! PlaylistGenerated(createPlaylist(), createPlaylistSettings())
    checkSentPlaylist(0, 0, 0)
  }

  /**
   * Tests whether a newly created empty playlist can be processed.
   */
  @Test def testEmptyPlaylistCreated() {
    actor ! PlaylistGenerated(List.empty, createPlaylistSettings())
    shutdownActor()
    sourceActor.ensureNoMessages()
  }

  /**
   * Prepares a test which requires an existing playlist. This method simply
   * sends a PlaylistGenerated message to the test actor.
   */
  private def prepareTestWithPlaylist() {
    actor ! PlaylistGenerated(createPlaylist(), createPlaylistSettings())
  }

  /**
   * Tests whether the actor can move to a valid index in the playlist.
   */
  @Test def testMoveToValidIndex() {
    val newIndex = 12
    whenExecuting(scanner, store) {
      prepareTestWithPlaylist()
      actor ! MoveTo(newIndex)
      sourceActor.skipMessages(PlaylistSize + 1)
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
    actor ! MoveTo(idx)
    shutdownActor()
    sourceActor.ensureNoMessages(PlaylistSize + 1)
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
    actor ! MoveRelative(5)
    actor ! MoveRelative(-2)
    sourceActor.skipMessages(PlaylistSize + 1)
    checkSentPlaylist(5, 0, 0, false)
    checkSentPlaylist(3, 0, 0)
  }

  /**
   * Tests a relative move which would cause an invalid index.
   */
  @Test def testMoveRelativeInvalidIndex() {
    prepareTestWithPlaylist()
    actor ! MoveRelative(PlaylistSize - 1)
    actor ! MoveRelative(5)
    sourceActor.skipMessages(PlaylistSize + 1)
    checkSentPlaylist(PlaylistSize - 1, 0, 0, false)
    checkSentPlaylist(PlaylistSize - 1, 0, 0)
  }

  /**
   * Tests a relative move operation if the current playlist is empty.
   */
  @Test def testMoveRelativeNoPlaylist() {
    whenExecuting(scanner, store) {
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
    val currentIndex = 16
    val pl = createPlaylist()
    store.savePlaylist(PlaylistID, createPersistentPlaylist(pl, currentIndex))
    val source = createSource(currentIndex)
    expectPlaylistProcessing(pl, None, None)
    whenExecuting(scanner, store) {
      actor ! ReadMedium(RootURI)
      prepareTestWithPlaylist()
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
      actor ! PlaybackSourceEnd(source, false)
    }

    val pl = createPlaylist()
    expectPlaylistProcessing(pl, None, None)
    store.savePlaylist(PlaylistID, createPersistentPlaylist(pl, 2, 0, 0))
    whenExecuting(scanner, store) {
      actor ! ReadMedium(RootURI)
      prepareTestWithPlaylist()
      sendSourceMessages(0)
      sendSourceMessages(1)
      val source = createSource(2)
      actor ! PlaybackSourceStart(source)
      actor ! PlaybackPositionChanged(CurrentPos, 2000, 500, source)
      actor ! PlaybackTimeChanged(CurrentTime)
      actor ! PlaybackSourceEnd(source, false)
      shutdownActor()
    }
  }

  /**
   * Tests whether a playlist can be persisted if it has been fully played.
   */
  @Test def testSavePlaylistComplete() {
    val source = createSource(PlaylistSize - 1)
    expectPlaylistProcessing(createPlaylist(), None, None)
    store.savePlaylist(PlaylistID, PlaylistCtrlActor.EmptyPlaylist)
    whenExecuting(scanner, store) {
      actor ! ReadMedium(RootURI)
      prepareTestWithPlaylist()
      actor ! PlaybackSourceStart(source)
      actor ! PlaybackSourceEnd(source, false)
      actor ! SavePlaylist
      shutdownActor()
    }
  }

  /**
   * Tests whether a save message is ignored if there is no playlist.
   */
  @Test def testSavePlaylistUndefined() {
    whenExecuting(scanner, store) {
      actor ! SavePlaylist
      shutdownActor()
    }
  }

  /**
   * Creates a mock actor and installs it as event listener at the Gateway.
   * @return the mock listener actor
   */
  private def installListener(): QueuingActor = {
    val listener = new QueuingActor
    listener.start()
    gateway.register(listener)
    listener
  }

  /**
   * Tests whether a newly created playlist is sent around as an event if there
   * is already a persistent playlist.
   */
  @Test def testPlaylistCreatedEventExistingPlaylist() {
    val listener = installListener()
    val pl = createPlaylist()
    val playlistData = createPersistentPlaylist(pl, 2)
    expectPlaylistProcessing(pl, Some(playlistData), Some(createSettings()))
    whenExecuting(scanner, store) {
      actor ! ReadMedium(RootURI)
      prepareTestWithPlaylist()
      shutdownActor()
    }
    listener.nextMessage() match {
      case pl: PlaylistDataImpl =>
        assert(PlaylistSize === pl.size)
        assert(2 === pl.startIndex)
        val settings = pl.settings
        assert(PlaylistName === settings.name)
        assert(PlaylistDesc === settings.description)
        for (i <- 0 until PlaylistSize) {
          assert(playlistURI(i) === pl.getURI(i))
          val srcData = pl.getAudioSourceData(i)
          assertTrue("Invalid title: " + srcData,
            playlistURI(i).contains(srcData.title))
          assertNull("Got an artist", srcData.artistName)
        }
      case msg => fail("Unexpected message: " + msg)
    }
    gateway.unregister(listener)
  }

  /**
   * Tests whether a playlist event is sent around if a playlist has to be
   * newly created.
   */
  @Test def testPlaylistCreatedEventNewPlaylist() {
    val listener = installListener()
    val settings = createPlaylistSettings()
    actor ! PlaylistGenerated(createPlaylist(), settings)
    listener.nextMessage() match {
      case pl: PlaylistDataImpl =>
        assert(0 === pl.startIndex)
        assertSame("Wrong settings", settings, pl.settings)
      case msg => fail("Unexpected message: " + msg)
    }
    gateway.unregister(listener)
  }

  /**
   * Tests whether an exit message is sent out when the test actor shuts down.
   */
  @Test def testActorExitedMessage() {
    val listener = installListener()
    val ctrlActor = actor
    shutdownActor()
    listener.expectMessage(ActorExited(ctrlActor))
    listener.ensureNoMessages()
    gateway.unregister(listener)
    listener.shutdown()
  }
}
