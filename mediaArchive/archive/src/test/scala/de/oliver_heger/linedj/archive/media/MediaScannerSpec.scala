package de.oliver_heger.linedj.archive.media

import java.nio.file.{Files, Path, Paths}

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.MediumID
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

object MediaScannerSpec {
  /** A set with file extensions to be excluded. */
  private val Exclusions = Set("TXT", "")

  /** The content of a file which is not part of a medium. */
  private val OtherFileContent = "12345678"

  /**
   * Helper method for checking whether all elements in a sub set are contained
   * in another set. Result is the first element in the sub set which was not
   * found in the set. A result of ''None'' means that the check was
   * successful.
   * @param set the full set
   * @param subSet the sub set
   * @tparam T the element type
   * @return an option with the first element that could not be found
   */
  private def checkContainsAll[T](set: Seq[T], subSet: Iterable[T]): Option[T] =
    subSet find (!set.contains(_))

  /**
   * Helper method for checking whether no path in the sub set is contained in
   * another set. A result of ''None'' means that the test succeeded.
   * @param set the sequence with paths to be checked
   * @return an option with an element with an excluded file extension
   */
  private def checkContainsNone[T](set: Seq[T], subSet: Iterable[T]): Option[T] =
    subSet find set.contains
}

/**
 * Test class for ''DirectoryScanner''.
 */
class MediaScannerSpec extends FlatSpec with Matchers with BeforeAndAfter with FileTestHelper {

  import de.oliver_heger.linedj.archive.media.MediaScannerSpec._

  after {
    tearDownTestFile()
  }

  /**
   * Creates a file with a given name in a given directory.
   * @param dir the directory
   * @param name the name of the file to be created
   * @param content the content
   * @return the path to the newly created file
   */
  private def createFile(dir: Path, name: String, content: String): Path =
    writeFileContent(dir resolve name, content)

  /**
   * Creates a directory below the given parent directory.
   * @param parent the parent directory
   * @param name the name of the new directory
   * @return the newly created directory
   */
  private def createDir(parent: Path, name: String): Path = {
    val dir = parent resolve name
    Files.createDirectory(dir)
    dir
  }

  /**
   * Generates a path to the test directory structure. This is similar to the
   * normal way of constructing path objects; however, the initial path element
   * is to be considered the test directory.
   * @param first the first (sub) component of the path
   * @param more optional additional path elements
   * @return the resulting path
   */
  private def constructPath(first: String, more: String*): Path = {
    val p = Paths.get(testDirectory.toString, first)
    if (more.isEmpty) p
    else Paths.get(p.toString, more: _*)
  }

  /**
   * Maps a relative file name (using '/' as path separator) to a path in the
   * test directory.
   * @param s the relative file name
   * @return the resulting path
   */
  private def toPath(s: String): Path = {
    val components = s.split("/")
    constructPath(components.head, components.tail: _*)
  }

  /**
   * Generates a set with path elements from the given list of strings.
   * @param s the strings
   * @return a set with transformed path elements
   */
  private def paths(s: String*): Set[Path] =
    s.toSet map toPath

  /**
   * Extracts path information from a list of media files.
   * @param files the sequence with file objects
   * @return a sequence with the extracts paths
   */
  private def extractPaths(files: Seq[FileData]): Seq[Path] =
    files map (_.path)

  /**
   * Creates a directory structure with test media files and directories.
   * @return a list with paths representing media
   */
  private def setUpDirectoryStructure(): List[Path] = {
    createFile(testDirectory, "test.txt", "some content")
    createFile(testDirectory, "noMedium1.mp3", OtherFileContent)
    val medium1 = createDir(testDirectory, "medium1")
    createFile(medium1, "noMedium2.mp3", OtherFileContent)
    createFile(medium1, "README.TXT", "BlaBla")
    createFile(medium1, "medium1.settings", "+")
    val sub1 = createDir(medium1, "aSub1")
    createFile(sub1, "medium1Song1.mp3", "*")
    val sub1Sub = createDir(sub1, "subSub")
    createFile(sub1Sub, "medium1Song2.mp3", "*")
    createFile(sub1Sub, "medium1Song3.mp3", "*")

    val medium2 = createDir(testDirectory, "medium2")
    createFile(medium2, "medium2.settings", "#")
    val sub2 = createDir(medium2, "sub2")
    createFile(sub2, "medium2Song1.mp3", "*")
    createFile(sub2, "noExtension", "?")

    val medium3 = createDir(sub2, "medium3")
    val sub3 = createDir(medium3, "sub3")
    createFile(sub3, "medium3Song1.mp3", "*")
    createFile(medium3, "medium3.settings", "+")

    val otherDir = createDir(testDirectory, "other")
    createFile(otherDir, "noMedium3.mp3", OtherFileContent)

    List(medium1, medium2, medium3)
  }

  /**
   * Creates a test scanner instance and let it scan the test directory structure.
   * @return the result of the scan operation
   */
  private def scan(): MediaScanResult = {
    val scanner = new MediaScanner(Exclusions)
    setUpDirectoryStructure()
    scanner scan testDirectory
  }

  /**
   * Returns the ID for an undefined medium as is expected to be produced by
   * the directory scanner.
   * @return the undefined medium ID
   */
  private def undefinedMediumID(): MediumID =
    MediumID(testDirectory.toString, None, ArchiveComponentID)

  "A MediaScanner" should "find media files in a directory structure" in {
    val expected = paths("noMedium1.mp3", "medium1/noMedium2.mp3", "other/noMedium3.mp3")
    val result = scan()

    result.root should be (testDirectory)
    checkContainsAll(extractPaths(result.mediaFiles(undefinedMediumID())), expected) should be(None)
  }

  it should "exclude files with configured extensions" in {
    val excluded = paths("test.txt", "medium2/sub2/noExtension")
    val result = scan()

    result.mediaFiles foreach { e =>
      checkContainsNone(e._2, excluded) should be(None)
    }
    checkContainsNone(extractPaths(result.mediaFiles(undefinedMediumID())), excluded) should be(None)
  }

  it should "detect all media directories" in {
    val expected = paths("medium1/medium1.settings", "medium2/medium2.settings",
      "medium2/sub2/medium3/medium3.settings")
    val result = scan()

    result.mediaFiles.keySet should have size 4
    checkContainsAll(result.mediaFiles.keySet.toList, expected)
  }

  it should "return the correct number of other files" in {
    val result = scan()
    result.mediaFiles(undefinedMediumID()) should have length 3
  }

  it should "not return an entry for other files if none are found" in {
    val paths = setUpDirectoryStructure()
    val scanner = new MediaScanner(Exclusions)
    val result = scanner scan paths(1)

    result.mediaFiles.keySet should not contain undefinedMediumID()
  }

  it should "set a correct archive component ID in all MediumID objects" in {
    val result = scan()

    result.mediaFiles.keys forall(_.archiveComponentID == ArchiveComponentID) shouldBe true
  }

  /**
   * Checks whether a medium has the specified content.
   * @param result the result of the scan operation
   * @param mediumDesc the relative path to the description file (without extension)
   * @param content the paths of the expected content
   * @return an option with a path that was not found
   */
  private def checkMedium(result: MediaScanResult, mediumDesc: String, content: String*):
  Option[String] = {
    val files = result.mediaFiles(MediumID.fromDescriptionPath(toPath(mediumDesc + ".settings"),
      ArchiveComponentID)) map (_.path.toAbsolutePath.toString)
    files should have length content.length
    val expPaths = paths(content: _*) map (_.toString)
    checkContainsAll(files, expPaths)
  }

  it should "return the correct content of a medium" in {
    val result = scan()

    checkMedium(result, "medium1/medium1", "medium1/aSub1/medium1Song1.mp3",
      "medium1/aSub1/subSub/medium1Song2.mp3", "medium1/aSub1/subSub/medium1Song3.mp3") shouldBe 'empty
    checkMedium(result, "medium2/medium2", "medium2/sub2/medium2Song1.mp3") shouldBe 'empty
    checkMedium(result, "medium2/sub2/medium3/medium3", "medium2/sub2/medium3/sub3/medium3Song1" +
      ".mp3") shouldBe 'empty
  }

  it should "determine correct file sizes" in {
    val result = scan()

    result.mediaFiles(MediumID.fromDescriptionPath(toPath("medium1/medium1.settings"),
      ArchiveComponentID)).head.size should be (1)
    result.mediaFiles(undefinedMediumID()).head.size should be (OtherFileContent.length)
  }
}
