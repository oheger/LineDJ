package de.oliver_heger.splaya.playlist.impl

import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import scala.collection.mutable.Set
import java.io.File
import java.io.PrintWriter
import java.io.FileWriter
import org.junit.Test
import org.junit.BeforeClass
import org.junit.Assert._
import org.apache.commons.vfs2.FileSystemManager
import org.apache.commons.vfs2.VFS
import org.junit.After
import java.io.BufferedReader
import java.io.InputStreamReader
import org.apache.commons.vfs2.FileSelectInfo
import org.apache.commons.vfs2.FileObject
import org.easymock.EasyMock
import org.apache.commons.vfs2.FileSystemException

/**
 * Test class for ''FSScannerImpl''.
 */
class TestFSScannerImpl extends JUnitSuite with EasyMockSugar {
  /** A list with the directories created by test cases. */
  private var listDirs = List.empty[File]

  /** A list with the files created by test cases. */
  private var listFiles = List.empty[File]

  /** A set with the URIs of the files in the music directory. */
  private var fileURIs: Set[String] = _

  /**
   * Performs cleanup. Removes the test files and directories created during
   * the last test case.
   */
  @After def tearDown() {
    listFiles.foreach(TestFSScannerImpl.remove(_))
    listDirs.foreach(TestFSScannerImpl.remove(_))
  }

  /**
   * Creates a new test directory. The parent directory can be undefined, then
   * the user's temporary directory is used.
   * @param parent the parent directory
   * @param name the name of the directory
   * @return the newly created directory
   */
  private def createDirectory(parent: File, name: String): File = {
    val parentFile = if (parent != null) parent else TestFSScannerImpl.tempDir
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
    content: String = TestFSScannerImpl.FileContent): File = {
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
   * Tests whether file extensions can be parsed successfully.
   */
  @Test def testSupportedFileExtensions() {
    val scan = new FSScannerImpl(TestFSScannerImpl.manager, "mp3,WAV, au;  clip")
    val exts = scan.supportedFileExtensions
    assert(4 === exts.size)
    assertTrue("Wrong set: " + exts, exts.subsetOf(Set("mp3", "wav", "au", "clip")))
  }

  /**
   * Tests the default file extensions.
   */
  @Test def testSupportedDefaultFileExtensions() {
    val scan = new FSScannerImpl(TestFSScannerImpl.manager)
    val exts = scan.supportedFileExtensions
    assert(1 === exts.size)
    assertTrue("Wrong default extension: " + exts, exts("mp3"))
  }

  /**
   * Tests a scan operation.
   */
  @Test def testScan() {
    val dir = setUpMusicDir()
    val scan = new FSScannerImpl(TestFSScannerImpl.manager)
    val files = scan.scan(dir.toURI.toString)
    assertEquals("Wrong number of files", fileURIs.size, files.size)
    files.foreach(TestFSScannerImpl.checkFile(_))
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
    val scan = new FSScannerImpl(TestFSScannerImpl.manager)
    whenExecuting(info, fo) {
      assertFalse("Wrong result", scan.acceptAudioFile(info))
    }
  }
}

object TestFSScannerImpl {
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
