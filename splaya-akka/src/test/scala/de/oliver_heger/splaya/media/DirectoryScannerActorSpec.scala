package de.oliver_heger.splaya.media

import java.nio.file.Paths

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import de.oliver_heger.splaya.media.DirectoryScannerActor.ScanPath
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

object DirectoryScannerActorSpec {
  /** A test path. */
  private val Path = Paths.get("TestPath")

  /** A test result object returned by the mock scanner. */
  private val TestResult = MediaScanResult(Path, Map(Path -> List(MediaFile(Path, 1))), List
    (MediaFile(Path, 2)))
}

/**
 * Test class for ''DirectoryScannerActor''.
 */
class DirectoryScannerActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  import de.oliver_heger.splaya.media.DirectoryScannerActorSpec._

  def this() = this(ActorSystem("DirectoryScannerActorSpec"))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
  }

  "A DirectoryScannerActor" should "handle a ScanPath message" in {
    val scanner = mock[DirectoryScanner]
    when(scanner.scan(Path)).thenReturn(TestResult)
    val scanActor = system.actorOf(Props(classOf[DirectoryScannerActor], scanner))

    scanActor ! ScanPath(Path)
    expectMsg(TestResult)
  }
}
