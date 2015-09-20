package de.oliver_heger.linedj.media

import java.nio.file.Paths

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.media.MediaScannerActor.ScanPath
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

object MediaScannerActorSpec {
  /** A test path. */
  private val Path = Paths.get("TestPath", "playlist.settings")

  /** A test result object returned by the mock scanner. */
  private val TestResult = MediaScanResult(Path, Map(MediumID.fromDescriptionPath(Path) -> List
    (FileData(Path, 1))))
}

/**
 * Test class for ''DirectoryScannerActor''.
 */
class MediaScannerActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  import de.oliver_heger.linedj.media.MediaScannerActorSpec._

  def this() = this(ActorSystem("MediaScannerActorSpec"))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
  }

  "A MediaScannerActor" should "handle a ScanPath message" in {
    val scanner = mock[MediaScanner]
    when(scanner.scan(Path)).thenReturn(TestResult)
    val scanActor = system.actorOf(Props(classOf[MediaScannerActor], scanner))

    scanActor ! ScanPath(Path)
    expectMsg(TestResult)
  }
}
