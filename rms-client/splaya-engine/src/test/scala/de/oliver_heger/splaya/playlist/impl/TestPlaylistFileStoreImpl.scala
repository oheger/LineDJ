package de.oliver_heger.splaya.playlist.impl

import org.scalatest.junit.JUnitSuite
import java.io.File
import org.junit.Before
import org.junit.After
import org.junit.Assert._
import scala.collection.mutable.ListBuffer
import org.junit.Test
import scala.xml.Elem
import java.io.PrintWriter
import java.io.FileWriter

/**
 * Test class for ''PlaylistFileStoreImpl''.
 */
class TestPlaylistFileStoreImpl extends JUnitSuite {
  /** Constant for a prefix for a test URI. */
  private val TestURI = "TestSong_"

  /** Constant for a test playlist ID. */
  private val TestID = "TestPlaylistID"

  /** Constant for the number of test URIs. */
  private val PlaylistSize = 16

  /** The test directory. */
  private var dataDir: File = _

  /** The test store. */
  private var store: PlaylistFileStoreImpl = _

  @Before def setUp() {
    val tempDir = new File(System.getProperty("java.io.tmpdir"))
    dataDir = new File(tempDir, "TestPlaylistFileStoreImpl")
    store = new PlaylistFileStoreImpl(dataDir.getAbsolutePath)
  }

  @After def tearDown() {
    if (dataDir.isDirectory()) {
      dataDir.listFiles() foreach (_.delete())
      assertTrue("Could not delete data directory", dataDir.delete())
    }
  }

  /**
   * Creates a test playlist.
   * @return the playlist
   */
  private def createPlaylist(): List[String] = {
    val builder = new ListBuffer[String]
    for (i <- 1 until PlaylistSize) {
      builder += (TestURI + i)
    }
    builder.toList
  }

  /**
   * Tests whether the correct data directory is maintained.
   */
  @Test def testDataDirectory() {
    assert(dataDir === store.dataDirectory)
    assertTrue("Directory not created", store.dataDirectory.exists)
  }

  /**
   * Tries to pass a data directory which cannot be created.
   */
  @Test(expected = classOf[IllegalArgumentException])
  def testDataDirectoryInvalid() {
    assertTrue("Could not create data dir", dataDir.mkdir())
    val f = new File(dataDir, "test.tmp")
    assertTrue("Could not create test file", f.createNewFile())
    val store2 = new PlaylistFileStoreImpl(f.getAbsolutePath)
    store2.dataDirectory.exists()
  }

  /**
   * Tests whether a playlist ID can be calculated.
   */
  @Test def testCalculatePlaylistID() {
    val id1 = store.calculatePlaylistID(createPlaylist())
    assertNotNull("No playlist ID", id1)
    assert(id1 === store.calculatePlaylistID(createPlaylist()))
  }

  /**
   * Tests whether the order of a playlist does not matter when generating the
   * playlist ID.
   */
  @Test def testCalculatePlaylistIDOrdering() {
    val pl1 = createPlaylist()
    val pl2 = pl1.reverse
    assert(store.calculatePlaylistID(pl1) === store.calculatePlaylistID(pl2))
  }

  /**
   * Tests whether different playlists result in different IDs.
   */
  @Test def testCalculatePlaylistIDDifferent() {
    val pl1 = createPlaylist()
    val id1 = store.calculatePlaylistID(pl1)
    val pl2 = "AnotherSong" :: pl1
    val id2 = store.calculatePlaylistID(pl2)
    assertNotSame("Same ID", id1, id2)
  }

  /**
   * Tries to load a non-existing playlist.
   */
  @Test def testLoadPlaylistNonExisting() {
    assert(None === store.loadPlaylist("nonExistingPlaylist"))
  }

  /**
   * Creates some test XML.
   * @return the root element of the test XML
   */
  private def createXML(): Elem =
    <playlist><name>Test</name><someData/></playlist>

  /**
   * Tests whether a playlist file can be written.
   */
  @Test def testSavePlaylistFile() {
    store.savePlaylist(TestID, createXML())
    val expFile = new File(dataDir, TestID + ".plist")
    assertTrue("File not found", expFile.isFile())
  }

  /**
   * Helper method for checking XML loaded from the store.
   * @param node the root element as option returned by the store
   */
  private def checkLoadedXML(node: Option[Elem]) {
    val expXML = List(createXML())
    assert(expXML === node.flatten)
  }

  /**
   * Tests whether a saved playlist file can be loaded again.
   */
  @Test def testSaveAndLoadPlaylistFile() {
    store.savePlaylist(TestID, createXML())
    checkLoadedXML(store.loadPlaylist(TestID))
  }

  /**
   * Tests whether a playlist settings file can be written.
   */
  @Test def testSaveSettingsFile() {
    store.saveSettings(TestID, createXML())
    val expFile = new File(dataDir, TestID + ".settings")
    assertTrue("File not found", expFile.isFile())
  }

  /**
   * Tests whether a saved settings file can be loaded again.
   */
  @Test def testSaveAndLoadSettingsFile() {
    store.saveSettings(TestID, createXML())
    checkLoadedXML(store.loadSettings(TestID))
  }

  /**
   * Tries to load an invalid XML document.
   */
  @Test def testLoadInvalidXML() {
    assertTrue("Could not create data dir", dataDir.mkdir())
    val file = new File(dataDir, TestID + ".plist")
    val out = new PrintWriter(new FileWriter(file))
    try {
      out.println("No valid XML content!")
    } finally {
      out.close()
    }
    assert(None === store.loadPlaylist(TestID))
  }
}
