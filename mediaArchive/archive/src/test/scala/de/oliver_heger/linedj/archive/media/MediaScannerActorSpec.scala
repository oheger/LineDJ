package de.oliver_heger.linedj.archive.media

import java.nio.file.{Path, Paths}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.scaladsl.Source
import akka.stream.{DelayOverflowStrategy, KillSwitch}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.archive.media.MediaScannerActor.ScanPath
import de.oliver_heger.linedj.io.stream.AbstractStreamProcessingActor.CancelStreams
import de.oliver_heger.linedj.io.{CloseRequest, FileData}
import de.oliver_heger.linedj.shared.archive.media.{MediumID, MediumInfo}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.annotation.tailrec
import scala.concurrent.duration._

object MediaScannerActorSpec {
  /** The root directory of the test folder structure. */
  private val RootPath = Paths get "musicArchive"

  /** The name of the test archive. */
  private val ArchiveName = "MyCoolMusicArchive"

  /** A list with some test files that will be scanned by test cases. */
  private val TestFiles = testMediaFiles()

  /** A test sequence number. */
  val SeqNo = 128

  /** The size of internal buffers. */
  private val BufferSize = 11

  /** The default timeout for parsing medium info files. */
  private val InfoParserTimeout = Timeout(1.minute)

  /**
    * Creates a ''FileData'' object for the specified path.
    *
    * @param path the path
    * @return the corresponding ''FileData''
    */
  private def fileData(path: Path): FileData = FileData(path, path.toString.length)

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
    val sub1 = medium1 resolve "songSub1"
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
      createFile(sub2, "medium2Text.txt"),
      createFile(sub3, "medium3Song1.mp3"),
      createFile(medium3, "medium3.settings"),
      createFile(otherDir, "noMedium3.mp3"))
  }

  /**
    * Returns default properties for the creation of a test actor instance.
    *
    * @param parser the medium info parser actor
    * @return creation properties for the test actor
    */
  private def testActorProps(parser: ActorRef): Props =
    Props(new MediaScannerActor(ArchiveName, Set.empty, Set.empty,
      BufferSize, parser, InfoParserTimeout) with ChildActorFactory)

  /**
    * Extracts only the file name from the specified file data.
    *
    * @param file the file data
    * @return the file name
    */
  private def extractFileName(file: FileData): String = file.path.getFileName.toString

  /**
    * Extracts the IDs of all media from the given sequence of result objects.
    *
    * @param results the result objects
    * @return a set with all encountered medium IDs
    */
  private def extractMedia(results: List[ScanSinkActor.CombinedResults]): Set[MediumID] =
    results.foldLeft(Set.empty[MediumID]) { (s, res) =>
      s ++ res.results.flatMap(_.result.scanResult.mediaFiles.keys)
    }

  /**
    * Searches in a list of scan results for result information for a specific
    * medium.
    *
    * @param results the result objects
    * @param mid     the medium ID
    * @return a tuple with the scan result and the medium information
    */
  private def findResultFor(results: List[ScanSinkActor.CombinedResults], mid: MediumID):
  (EnhancedMediaScanResult, Option[MediumInfo]) = {
    val optMediumRes = results.flatMap(_.results).find(
      _.result.scanResult.mediaFiles.contains(mid))
    val mediumRes = optMediumRes.get
    (mediumRes.result, mediumRes.info.get(mid))
  }
}

/**
  * Test class for ''DirectoryScannerActor''.
  */
class MediaScannerActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar
  with FileTestHelper {

  import de.oliver_heger.linedj.archive.media.MediaScannerActorSpec._

  def this() = this(ActorSystem("MediaScannerActorSpec"))

  override protected def beforeAll(): Unit = {
    resolveTestFiles() foreach { file =>
      writeFileContent(file.path, FileTestHelper.TestData.substring(file.size.toInt))
    }
  }

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    tearDownTestFile()
  }

  /**
    * Resolves all test files against the current temp directory.
    *
    * @return the resolved file data objects
    */
  private def resolveTestFiles(): List[FileData] =
    TestFiles map { f =>
      val path = createPathInDirectory(f.path.toString)
      f.copy(path = path)
    }

  /**
    * Returns a set with all defined medium IDs for the test directory
    * structure.
    *
    * @return a set with all defined medium IDs
    */
  private def allDefinedMediumIDs(): Set[MediumID] =
    TestFiles.map(_.path.toString)
      .filter(_.endsWith(".settings"))
      .map { p =>
        val path = testDirectory resolve p
        MediumID(path.getParent.toString, Some(path.toString), ArchiveName)
      }.toSet

  /**
    * Returns an ID for a test medium that contains the specified key.
    * Using keys like ''medium1'', or ''medium2'', a specific medium can be
    * selected.
    *
    * @param key the key
    * @return the medium ID for this key
    */
  private def testMediumID(key: String): MediumID =
    allDefinedMediumIDs().find(_.mediumURI contains key).get

  "A MediaScannerActor" should "return correct creation Props" in {
    val exclusions = Set("FOO", "BAR")
    val inclusions = Set("BAZ")
    val parser = TestProbe().ref
    val parseTimeout = Timeout(11.seconds)
    val props = MediaScannerActor(ArchiveName, exclusions, inclusions, BufferSize,
      parser, parseTimeout)

    classOf[MediaScannerActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should be(List(ArchiveName, exclusions, inclusions, BufferSize,
      parser, parseTimeout))
  }

  it should "find all defined media in a directory structure" in {
    val helper = new ScannerActorTestHelper

    val results = helper.scanAndGetResults()
    extractMedia(results) should contain allElementsOf allDefinedMediumIDs()
  }

  it should "read the content of media" in {
    val helper = new ScannerActorTestHelper
    val results = helper.scanAndGetResults()

    def filesFor(key: String): List[String] = {
      val mid = testMediumID(key)
      findResultFor(results, mid)._1.scanResult.mediaFiles(mid)
        .map(extractFileName)
    }

    val m1Files = filesFor("medium1")
    m1Files should have size 3
    m1Files forall (_.startsWith("medium1Song")) shouldBe true
    val m2Files = filesFor("medium2")
    m2Files should have size 2
    m2Files should contain only("medium2Song1.mp3", "medium2Text.txt")
    val m3Files = filesFor("medium3")
    m3Files should have size 1
    m3Files should contain only "medium3Song1.mp3"
  }

  it should "read the content of the undefined medium" in {
    val helper = new ScannerActorTestHelper
    val results = helper.scanAndGetResults()

    val optResUndef = results.flatMap(_.results)
      .find(_.result.scanResult.mediaFiles.keys.exists(_.mediumDescriptionPath.isEmpty))
    val resUndef = optResUndef.get
    val mid = resUndef.result.scanResult.mediaFiles.keys
      .find(_.mediumDescriptionPath.isEmpty).get
    val files = resUndef.result.scanResult.mediaFiles(mid).map(extractFileName)
    files should have size 3
    files forall (_.startsWith("noMedium")) shouldBe true
  }

  it should "obtain medium information for defined media" in {
    val helper = new ScannerActorTestHelper
    val results = helper.scanAndGetResults()

    allDefinedMediumIDs() foreach { mid =>
      val (_, optInfo) = findResultFor(results, mid)
      val info = optInfo.get
      info.mediumID should be(mid)
      info.name should be(mid.mediumDescriptionPath.get)
    }
  }

  it should "correctly handle an exception during a scan operation" in {
    val helper = new ScannerActorTestHelper

    val results = helper.scan(Paths get "nonExistingPath")
      .waitForScanComplete()
      .fetchAllResults()
    results shouldBe empty
  }

  it should "support canceling a scan operation" in {
    val fProps: ActorRef => Props = parserActor =>
      Props(new MediaScannerActor(ArchiveName, Set.empty, Set.empty, BufferSize,
        parserActor, InfoParserTimeout) with ChildActorFactory {
        override private[media] def createSource(path: Path): Source[Path, Any] = {
          super.createSource(path).delay(200.milliseconds, DelayOverflowStrategy.backpressure)
        }
      })
    val helper = new ScannerActorTestHelper(fProps)

    val results = helper.scan()
      .post(CancelStreams)
      .waitForScanComplete()
      .fetchAllResults()
    extractMedia(results).size should be < 4
  }

  it should "unregister kill switches after stream processing" in {
    val refKillSwitch = new AtomicReference[KillSwitch]
    val fProps: ActorRef => Props = parserActor =>
      Props(new MediaScannerActor(ArchiveName, Set.empty, Set.empty, BufferSize,
        parserActor, InfoParserTimeout) with ChildActorFactory {
        override private[media] def runStream(source: Source[Path, Any], root: Path,
                                              sinkActor: ActorRef): KillSwitch = {
          val res = super.runStream(source, root, sinkActor)
          if (refKillSwitch.get() != null) res
          else {
            val ks = mock[KillSwitch]
            refKillSwitch set ks
            ks
          }
        }
      })
    val helper = new ScannerActorTestHelper(fProps)

    helper.scan()
      .waitForScanComplete()
      .post(CancelStreams)
    verify(refKillSwitch.get(), never()).shutdown()
  }

  it should "support excluding files" in {
    val fProps: ActorRef => Props = parserActor =>
      Props(new MediaScannerActor(ArchiveName, Set("TXT"), Set.empty, BufferSize,
        parserActor, InfoParserTimeout) with ChildActorFactory)
    val helper = new ScannerActorTestHelper(fProps)

    val results = helper.scanAndGetResults()
    val mid = testMediumID("medium2")
    val m2Results = findResultFor(results, mid)
    val files = m2Results._1.scanResult.mediaFiles(mid).map(extractFileName)
    files should contain only "medium2Song1.mp3"
  }

  it should "support including files (with a higher preference than excluding)" in {
    val fProps: ActorRef => Props = parserActor =>
      Props(new MediaScannerActor(ArchiveName, Set("TXT"), Set("TXT"), BufferSize,
        parserActor, InfoParserTimeout) with ChildActorFactory)
    val helper = new ScannerActorTestHelper(fProps)

    val results = helper.scanAndGetResults()
    val mid = testMediumID("medium2")
    val m2Results = findResultFor(results, mid)
    val files = m2Results._1.scanResult.mediaFiles(mid).map(extractFileName)
    files should contain only "medium2Text.txt"
  }

  it should "handle timeouts when parsing medium description files" in {
    val fProps: ActorRef => Props = parserActor =>
      Props(new MediaScannerActor(ArchiveName, Set("TXT"), Set.empty, BufferSize,
        parserActor, Timeout(500.millis)) with ChildActorFactory)
    val helper = new ScannerActorTestHelper(fProps)

    val results = helper.disableInfoParserActor().scanAndGetResults()
    results should have size 0
  }

  /**
    * A test helper class managing a test actor and its dependencies.
    *
    * @param fProps a function to adapt the properties of the test actor
    */
  private class ScannerActorTestHelper(fProps: ActorRef => Props = testActorProps) {
    /** The queue in which results are stored. */
    private val resultQueue = new LinkedBlockingQueue[ScanSinkActor.CombinedResults]

    /** The flag to detect a completed scan operation. */
    private val scanCompleted = new AtomicBoolean

    /** The actor that processes results from the test actor. */
    private val resultConsumerActor =
      system.actorOf(Props(classOf[ResultsConsumerActor], resultQueue, scanCompleted))

    /** The mock info parser actor. */
    private val infoParser = system.actorOf(Props[MockMediumInfoParserActor]())

    /** The actor to be tested. */
    private val scanActor = createTestActor()

    /**
      * Posts the specified message to the actor under test.
      *
      * @param msg the message
      * @return this test helper
      */
    def post(msg: Any): ScannerActorTestHelper = {
      scanActor ! msg
      this
    }

    /**
      * Starts a scan operation on the test directory.
      *
      * @param root optional root path to scan
      * @return this test helper
      */
    def scan(root: Path = testDirectory resolve RootPath): ScannerActorTestHelper = {
      scanActor.tell(ScanPath(root, SeqNo), resultConsumerActor)
      this
    }

    /**
      * Returns the next result object that was received by the consumer actor.
      *
      * @return the next result object
      */
    def nextResult(): ScanSinkActor.CombinedResults = {
      val res = resultQueue.poll(5, TimeUnit.SECONDS)
      res should not be null
      res.seqNo should be(SeqNo)
      res
    }

    /**
      * Returns a flag whether more results are available. Note that this
      * method works reliably only after the end of the scan operation.
      *
      * @return a flag whether more results are available
      */
    def hasMoreResults: Boolean = !resultQueue.isEmpty

    /**
      * Returns a sequence with all result objects received by the result
      * consumer actor. Note that this method works reliably only after the end
      * of the scan operation.
      *
      * @return a sequence with all received result objects
      */
    def fetchAllResults(): List[ScanSinkActor.CombinedResults] = {
      @tailrec def fetchNextResult(res: List[ScanSinkActor.CombinedResults]):
      List[ScanSinkActor.CombinedResults] =
        if (hasMoreResults) fetchNextResult(nextResult() :: res)
        else res

      fetchNextResult(Nil).reverse
    }

    /**
      * Waits for the end of the current scan operation. This method waits
      * for the arrival of the scan completed message from the test actor.
      *
      * @return this test helper
      */
    def waitForScanComplete(): ScannerActorTestHelper = {
      awaitCond(scanCompleted.get())
      this
    }

    /**
      * Scans the test directory, waits for the completion of the scan
      * operation, and returns the list with all results.
      *
      * @return the list with all results generated during the scan operation
      */
    def scanAndGetResults(): List[ScanSinkActor.CombinedResults] =
      scan().waitForScanComplete().fetchAllResults()

    /**
      * Disables the mock medium info parser actor, so that no parse results
      * will be sent.
      *
      * @return this test helper
      */
    def disableInfoParserActor(): ScannerActorTestHelper = {
      infoParser ! CloseRequest
      this
    }

    /**
      * Creates an instance of the test actor.
      *
      * @return the test actor instance
      */
    private def createTestActor(): ActorRef = {
      system.actorOf(fProps(infoParser))
    }
  }

}

/**
  * A test actor class simulating the media manager regarding results
  * consumption. Each result object passed to the actor is stored in the queue
  * provided, and an ACK message is sent. On receiving a message about the
  * completion of the scan request, the given atomic boolean is set to
  * '''true'''.
  *
  * @param resultsQueue the queue for storing results
  * @param completed    flag to be set when the scan operation is done
  */
class ResultsConsumerActor(resultsQueue: LinkedBlockingQueue[ScanSinkActor.CombinedResults],
                           completed: AtomicBoolean)
  extends Actor {
  override def receive: Receive = {
    case r: ScanSinkActor.CombinedResults =>
      resultsQueue offer r
      sender() ! ScanSinkActor.Ack

    case MediaScannerActor.PathScanCompleted(req) if req.seqNo == MediaScannerActorSpec.SeqNo =>
      completed set true
  }
}

/**
  * A mock implementation of an actor that parses a medium description file.
  * This implementation just generates a dummy ''MediumInfo'' based on the
  * parameters passed in.
  *
  * In order to test timeouts, an instance can be deactivated by sending it a
  * ''CloseRequest'' message. Then no answers are sent.
  */
class MockMediumInfoParserActor extends Actor {
  /** Flag whether this actor is active. */
  private var active = true

  override def receive: Receive = {
    case req@MediumInfoParserActor.ParseMediumInfo(path, mid, _) if active =>
      val info = MediumInfo(mediumID = mid, name = path.toString, description = "",
        orderMode = "", orderParams = "", checksum = "0")
      sender() ! MediumInfoParserActor.ParseMediumInfoResult(req, info)

    case CloseRequest =>
      active = false
  }
}
