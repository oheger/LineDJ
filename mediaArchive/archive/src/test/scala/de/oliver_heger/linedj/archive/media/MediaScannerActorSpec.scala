package de.oliver_heger.linedj.archive.media

import java.nio.file.{Path, Paths}
import java.util.concurrent.atomic.AtomicReference

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.{DelayOverflowStrategy, KillSwitch}
import akka.stream.scaladsl.Source
import akka.testkit.{ImplicitSender, TestKit}
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.archive.media.MediaScannerActor.{ScanPath, ScanPathResult}
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.io.stream.AbstractStreamProcessingActor.CancelStreams
import de.oliver_heger.linedj.shared.archive.media.MediumID
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.Future
import scala.concurrent.duration._

object MediaScannerActorSpec {
  /** The root directory of the test folder structure. */
  private val RootPath = Paths get "musicArchive"

  /** The name of the test archive. */
  private val ArchiveName = "MyCoolMusicArchive"

  /** A test sequence number. */
  private val SeqNo = 128

  /**
    * The ID for an undefined medium as is expected to be produced by
    * the directory scanner.
    */
  private val UndefinedMediumID =
    MediumID(RootPath.toString, None, ArchiveName)

  /**
    * Helper method for checking whether all elements in a sub set are contained
    * in another set. Result is the first element in the sub set which was not
    * found in the set. A result of ''None'' means that the check was
    * successful.
    *
    * @param set    the full set
    * @param subSet the sub set
    * @tparam T the element type
    * @return an option with the first element that could not be found
    */
  private def checkContainsAll[T](set: Seq[T], subSet: Iterable[T]): Option[T] =
    subSet find (!set.contains(_))

  /**
    * Generates a path to the test directory structure. This is similar to the
    * normal way of constructing path objects; however, the initial path element
    * is to be considered the test directory.
    *
    * @param first the first (sub) component of the path
    * @param more  optional additional path elements
    * @return the resulting path
    */
  private def constructPath(first: String, more: String*): Path = {
    val p = Paths.get(RootPath.toString, first)
    if (more.isEmpty) p
    else Paths.get(p.toString, more: _*)
  }

  /**
    * Maps a relative file name (using '/' as path separator) to a path in the
    * test directory.
    *
    * @param s the relative file name
    * @return the resulting path
    */
  private def toPath(s: String): Path = {
    val components = s.split("/")
    constructPath(components.head, components.tail: _*)
  }

  /**
    * Generates a set with path elements from the given list of strings.
    *
    * @param s the strings
    * @return a set with transformed path elements
    */
  private def paths(s: String*): Set[Path] =
    s.toSet map toPath

  /**
    * Extracts path information from a list of media files.
    *
    * @param files the sequence with file objects
    * @return a sequence with the extracts paths
    */
  private def extractPaths(files: Seq[FileData]): Seq[Path] =
    files map (f => Paths get f.path)

  /**
    * Creates a ''FileData'' object for the specified path.
    *
    * @param path the path
    * @return the corresponding ''FileData''
    */
  private def fileData(path: Path): FileData =
    FileData(path.toString, path.toString.length)

  /**
    * Creates a ''FileData'' object representing a file in a directory.
    *
    * @param dir  the directory
    * @param name the name of the file
    * @return the resulting ''FileData''
    */
  private def createFile(dir: Path, name: String): FileData =
    fileData(dir resolve name)

  /**
    * Returns a list with data about files organized in media.
    *
    * @return a list with file data objects representing media
    */
  private def testMediaFiles(): List[FileData] = {
    val medium1 = RootPath resolve "medium1"
    val sub1 = medium1 resolve "aSub1"
    val sub1Sub = sub1 resolve "subSub"
    val medium2 = RootPath resolve "medium2"
    val sub2 = medium2 resolve "sub2"
    val medium3 = sub2 resolve "medium3"
    val sub3 = medium3 resolve "sub3"
    val otherDir = RootPath resolve "other"

    List(createFile(RootPath, "noMedium1.mp3"),
      createFile(medium1, "noMedium2.mp3"),
      createFile(medium1, "medium1.settings"),
      createFile(sub1, "medium1Song1.mp3"),
      createFile(sub1Sub, "medium1Song2.mp3"),
      createFile(sub1Sub, "medium1Song3.mp3"),
      createFile(medium2, "medium2.settings"),
      createFile(sub2, "medium2Song1.mp3"),
      createFile(sub3, "medium3Song1.mp3"),
      createFile(medium3, "medium3.settings"),
      createFile(otherDir, "noMedium3.mp3"))
  }

  /**
    * Adds some entries about directories to the list of media files. These
    * should be ignored by the stream.
    *
    * @param data the list with media files
    * @return the enhanced list with directories
    */
  private def addDirectories(data: List[FileData]): List[FileData] = {
    def dirData(name: String): FileData = FileData(RootPath.resolve(name).toString, -1)

    dirData("dir1") :: dirData("other_dir") :: data
  }
}

/**
  * Test class for ''DirectoryScannerActor''.
  */
class MediaScannerActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar
  with FileTestHelper {

  import de.oliver_heger.linedj.archive.media.MediaScannerActorSpec._

  def this() = this(ActorSystem("MediaScannerActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    tearDownTestFile()
  }

  /**
    * Creates a test actor instance that processes the specified source of
    * file data objects
    *
    * @param source the source to be processed
    * @return the test actor reference
    */
  private def createActorForSource(source: Source[FileData, Any]): ActorRef = {
    val props = Props(new MediaScannerActor(ArchiveName, Set.empty) {
      override private[media] def createSource(path: Path): Source[FileData, Any] = source
    })
    system.actorOf(props)
  }

  /**
    * Creates a test actor that processes the specified list of media file
    * objects.
    *
    * @param fileData the list with file data objects
    * @return the test actor reference
    */
  private def createActorForFiles(fileData: List[FileData]): ActorRef =
    createActorForSource(Source(fileData))

  /**
    * Triggers a scan operation for the specified test actor.
    *
    * @param actor the test actor
    * @param root  the root path to be scanned
    * @return the result
    */
  private def scan(actor: ActorRef, root: Path = RootPath): MediaScanResult = {
    val request = ScanPath(root, SeqNo)
    actor ! request
    val result = expectMsgType[ScanPathResult]
    result.request should be(request)
    result.result
  }

  "A MediaScannerActor" should "find media files in a directory structure" in {
    val expected = paths("noMedium1.mp3", "medium1/noMedium2.mp3", "other/noMedium3.mp3")
    val scanActor = createActorForFiles(testMediaFiles())

    val result = scan(scanActor)
    result.root should be(RootPath)
    checkContainsAll(extractPaths(result.mediaFiles(UndefinedMediumID)),
      expected) should be(None)
  }

  it should "detect all media directories" in {
    val expected = paths("medium1/medium1.settings", "medium2/medium2.settings",
      "medium2/sub2/medium3/medium3.settings")
    val scanActor = createActorForFiles(testMediaFiles())
    val result = scan(scanActor)

    result.mediaFiles.keySet should have size 4
    val settingsPaths = result.mediaFiles.keys.map(m =>
      Paths.get(m.mediumDescriptionPath.getOrElse(""))).toList
    checkContainsAll(settingsPaths, expected) should be(None)
  }

  it should "return the correct number of other files" in {
    val scanActor = createActorForFiles(testMediaFiles())
    val result = scan(scanActor)

    result.mediaFiles(UndefinedMediumID) should have length 3
  }

  it should "not return an entry for other files if none are found" in {
    val files = testMediaFiles() filterNot (_.path contains "noMedium")
    val scanActor = createActorForFiles(files)
    val result = scan(scanActor)

    result.mediaFiles.keySet should not contain UndefinedMediumID
  }

  it should "set a correct archive component ID in all MediumID objects" in {
    val scanActor = createActorForFiles(testMediaFiles())
    val result = scan(scanActor)

    result.mediaFiles.keys forall (_.archiveComponentID == ArchiveName) shouldBe true
  }

  it should "filter out directories from the source" in {
    val paths = addDirectories(testMediaFiles())
    val scanActor = createActorForFiles(paths)
    val result = scan(scanActor)

    val allFiles = result.mediaFiles.values.flatten
    allFiles.filter(_.path.contains("dir")) shouldBe 'empty
  }

  it should "correctly scan a data directory" in {
    val mediaDir = createPathInDirectory("music")

    def writeMediaFile(name: String): Path = {
      val path = mediaDir resolve name
      writeFileContent(path, name)
    }

    writeFileContent(createPathInDirectory("test.settings"), "*")
    val file1 = writeMediaFile("coolMusic1.mp3")
    writeMediaFile("lyrics.txt")
    val file2 = writeMediaFile("moreMusic.mp3")
    writeMediaFile("noExtension")
    val Exclusions = Set("TXT", "")
    val scanActor = system.actorOf(Props(classOf[MediaScannerActor], ArchiveName, Exclusions))

    val result = scan(scanActor, testDirectory)
    result.mediaFiles should have size 1
    val allFiles = result.mediaFiles.values.flatten.toList
    allFiles should have size 2
    checkContainsAll(extractPaths(allFiles), List(file1, file2)) should be(None)
  }

  it should "correctly handle an exception during a scan operation" in {
    val scanActor = system.actorOf(Props(classOf[MediaScannerActor], ArchiveName, Set.empty))

    val result = scan(scanActor)
    result.root should be(RootPath)
    result.mediaFiles shouldBe 'empty
  }

  it should "support canceling a scan operation" in {
    val files = testMediaFiles()
    val source = Source(files).delay(200.milliseconds, DelayOverflowStrategy.backpressure)
    val scanActor = createActorForSource(source)
    scanActor ! ScanPath(RootPath, SeqNo)

    scanActor ! CancelStreams
    val result = expectMsgType[ScanPathResult]
    val expectedFiles = files.filterNot(_.path.endsWith(".settings"))
    val allFiles = result.result.mediaFiles.values.flatten.toList
    allFiles.size should be < expectedFiles.size
  }

  it should "unregister kill switches after stream processing" in {
    val refKillSwitch = new AtomicReference[KillSwitch]
    val scanActor = system.actorOf(Props(new MediaScannerActor(ArchiveName, Set.empty) {
      override private[media] def createSource(path: Path): Source[FileData, Any] =
        Source(testMediaFiles())

      override private[media] def runStream(source: Source[FileData, Any]):
      (KillSwitch, Future[Seq[FileData]]) = {
        val t = super.runStream(source)
        if (refKillSwitch.get() != null) t
        else {
          val ks = mock[KillSwitch]
          refKillSwitch set ks
          (ks, t._2)
        }
      }
    }))

    scan(scanActor)
    scanActor ! CancelStreams
    scan(scanActor)
    verify(refKillSwitch.get(), never()).shutdown()
  }
}
