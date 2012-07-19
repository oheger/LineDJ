package de.oliver_heger.splaya.fs.impl

import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar
import java.io.File
import org.junit.BeforeClass
import org.junit.Assert._
import org.apache.commons.vfs2.FileSystemManager
import org.apache.commons.vfs2.VFS
import java.io.BufferedReader
import java.io.InputStreamReader
import org.junit.After
import java.io.PrintWriter
import java.io.FileWriter
import org.junit.Test
import org.junit.Before
import org.apache.commons.vfs2.FileSelectInfo
import org.apache.commons.vfs2.FileObject
import org.easymock.EasyMock
import org.apache.commons.vfs2.FileSystemException

/**
 * Test class for ''FSServiceImpl'' which tests functionality related to
 * reading the content of a source medium.
 */
class TestFSServiceImplScan extends JUnitSuite with EasyMockSugar {
  /** A list with the directories created by test cases. */
  private var listDirs = List.empty[File]

  /** A list with the files created by test cases. */
  private var listFiles = List.empty[File]

  /** A set with the URIs of the files in the music directory. */
  private var fileURIs: Set[String] = _

  /** The service to be tested. */
  private var service: FSServiceImpl = _

  @Before def setUp() {
    service = new FSServiceImpl
    service.activate()
  }

  /**
   * Performs cleanup. Removes the test files and directories created during
   * the last test case.
   */
  @After def tearDown() {
    listFiles.foreach(TestFSServiceImplScan.remove(_))
    listDirs.foreach(TestFSServiceImplScan.remove(_))
  }

  /**
   * Creates a new test directory. The parent directory can be undefined, then
   * the user's temporary directory is used.
   * @param parent the parent directory
   * @param name the name of the directory
   * @return the newly created directory
   */
  private def createDirectory(parent: File, name: String): File = {
    val parentFile = if (parent != null) parent else TestFSServiceImplScan.tempDir
    val newDir = new File(parentFile, name)
    assertTrue("Could not create directory: " + newDir, newDir.mkdir())
    listDirs = newDir :: listDirs
    newDir
  }

  /**
   * Creates a directory structure with audio files. For each audio file an
   * entry is created in the ''fileURIs'' set.
   * @return the root directory of the music directory structure
   */
  private def setUpMusicDir(): File = {
    fileURIs = Set.empty[String]
    val rootDir = createDirectory(null, "music")
    val audioFileData = musicData()

    for (artist <- audioFileData.keys) {
      val artistDir = createDirectory(rootDir, artist)
      for (album <- audioFileData(artist)) {
        val albumDir = createDirectory(artistDir, album.name)
        createAlbumTrackFiles(albumDir, album.tracks)
      }
    }
    rootDir
  }

  /**
   * Generates a map with information about test audio files.
   * @return the map
   */
  private def musicData(): Map[String, List[AlbumData]] = {
    val oldfieldAlbums = List(AlbumData("Crisis", 6), AlbumData("Discovery", 9),
      AlbumData("Islands", 8), AlbumData("QE2", 9),
      AlbumData("Tubular Bells", 2))
    val floydAlbums = List(AlbumData("Animals", 5),
      AlbumData("At\u00f6m Heart Mother", 7),
      AlbumData("Dark Side of the Moon", 5), AlbumData("The Wall", 16))
    Map("Mike Oldfield" -> oldfieldAlbums, "Pink Floyd" -> floydAlbums)
  }

  /**
   * Creates the files representing the tracks of the album. The method will
   * create some other files, too, which should be ignored by the filter.
   * @param albumDir the directory for the album
   * @param tracks the number of tracks of this album
   */
  private def createAlbumTrackFiles(albumDir: File, tracks: Int) {
    for (i <- 1 until tracks) {
      fileURIs += createFile(albumDir, fileName(i)).toURI.toString
    }
    createFile(albumDir, "cover.jpg", "someCover")
    createFile(albumDir, "README", "someReadMe")
  }

  /**
   * Generates the name of a test file.
   * @param the track number of the file
   * @return the name of this test file
   */
  private def fileName(track: Int): String =
    String.format("%02d - TRACK.mp3", track.asInstanceOf[Object])

  /**
   * Creates a temporary file with a test content.
   * @param dir the directory for the file to create
   * @param the file name
   * @param content the content of the file as string
   */
  private def createFile(dir: File, name: String,
    content: String = TestFSServiceImplScan.FileContent): File = {
    val file = new File(dir, name)
    val out = new PrintWriter(new FileWriter(file))
    try {
      out.println(name)
      out.println(content)
    } finally {
      out.close()
    }
    listFiles = file :: listFiles
    file
  }

  /**
   * Helper method for testing a successful scan operation.
   * @param exts the set of extensions to be passed in
   */
  private def checkScan(exts: Set[String]) {
    val dir = setUpMusicDir()
    val files = service.scan(dir.toURI.toString, exts)
    assertEquals("Wrong number of files", fileURIs.size, files.size)
    files.foreach(TestFSServiceImplScan.checkFile(_))
  }

  /**
   * Tests a scan operation with default extensions.
   */
  @Test def testScanDefaultExtensions() {
    checkScan(null)
  }

  /**
   * Tests a scan operation if the extensions are passed in.
   */
  @Test def testScanWithExtensions() {
    checkScan(Set("MP3", "someOtherExt"))
  }

  /**
   * Tests whether the filter for file extensions is actually applied.
   */
  @Test def testScanNoHits() {
    val dir = setUpMusicDir()
    val files = service.scan(dir.toURI.toString, Set("unsupported"))
    assertTrue("Got URIs", files.isEmpty)
  }

  /**
   * Tests the file filter if an exception is thrown.
   */
  @Test def testAcceptAudioFileException() {
    val info = mock[FileSelectInfo]
    val fo = mock[FileObject]
    expecting {
      EasyMock.expect(info.getFile()).andReturn(fo);
      EasyMock.expect(fo.getType()).andThrow(
        new FileSystemException("TestException"));
    }
    whenExecuting(info, fo) {
      assertFalse("Wrong result", service.acceptAudioFile(info, Set("test")))
    }
  }
}

object TestFSServiceImplScan {
  /** Constant for the property for the temporary directory. */
  private val PropTempDir = "java.io.tmpdir"

  /** Constant for the content of the test files. */
  private val FileContent = "TestFileContent"

  /** The VFS file system manager. */
  private var manager: FileSystemManager = _

  /** The temporary directory. */
  private var tempDir: File = _

  @BeforeClass def setUpBeforeClass() {
    manager = VFS.getManager()
    tempDir = new File(System.getProperty(PropTempDir))
  }

  /**
   * Helper method for removing a file.
   * @param f the file to be removed
   */
  private def remove(f: File) {
    assertTrue("Could not remove: " + f, f.delete())
  }

  /**
   * Checks the content of the specified file.
   * @param uri the URI to the test file
   * @throws IOException if an error occurs
   */
  private def checkFile(uri: String) {
    val fo = manager.resolveFile(uri);
    assertTrue("File does not exist: " + uri, fo.exists());
    val in = new BufferedReader(new InputStreamReader(fo.getContent()
      .getInputStream()));
    try {
      val line = in.readLine();
      assertTrue("Wrong name: " + line, uri.endsWith(line));
      assertEquals("Wrong content of file", FileContent, in.readLine());
    } finally {
      in.close();
    }
  }
}

/**
 * A simple data class for describing an album. It is used to generate the test
 * audio data files.
 */
private case class AlbumData(name: String, tracks: Int)
