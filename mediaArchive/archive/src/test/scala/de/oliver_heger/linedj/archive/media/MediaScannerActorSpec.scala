package de.oliver_heger.linedj.archive.media

import java.nio.file.Paths

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import de.oliver_heger.linedj.archive.media.MediaScannerActor.ScanPath
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.MediumID
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

object MediaScannerActorSpec {
  /** A test path. */
  private val Path = Paths.get("TestPath", "playlist.settings")

  /** A test result object returned by the mock scanner. */
  private val TestResult = MediaScanResult(Path, Map(MediumID.fromDescriptionPath(Path) -> List
    (FileData(Path.toString, 1))))
}

/**
 * Test class for ''DirectoryScannerActor''.
 */
class MediaScannerActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  import de.oliver_heger.linedj.archive.media.MediaScannerActorSpec._

  def this() = this(ActorSystem("MediaScannerActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A MediaScannerActor" should "handle a ScanPath message" in {
    val scanner = mock[MediaScanner]
    when(scanner.scan(Path)).thenReturn(TestResult)
    val scanActor = system.actorOf(Props(classOf[MediaScannerActor], scanner))

    scanActor ! ScanPath(Path)
    expectMsg(TestResult)
  }
}
