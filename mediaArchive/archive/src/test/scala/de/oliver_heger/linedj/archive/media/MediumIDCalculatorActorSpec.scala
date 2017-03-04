package de.oliver_heger.linedj.archive.media

import java.nio.file.{Path, Paths}

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import de.oliver_heger.linedj.archive.media.MediumIDCalculatorActor.CalculateMediumID
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.MediumID
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

/**
 * Test class for ''MediumIDCalculatorActor''.
 */
class MediumIDCalculatorActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {
  def this() = this(ActorSystem("MediumIDCalculatorActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
   * Creates a test actor instance with the passed in calculator.
   * @param calc the calculator to be passed to the actor
   * @return the reference to the test actor
   */
  private def calculatorActor(calc: MediumIDCalculator): ActorRef =
    system.actorOf(Props(classOf[MediumIDCalculatorActor], calc))

  /**
    * Helper method to create a ''FileData'' instance.
    * @param p the path
    * @param sz the size
    * @return the ''FileData''
    */
  private def fileData(p: Path, sz: Int): FileData =
    FileData(p.toString, sz)

  "A MediumCalculatorActor" should "calculate a medium ID" in {
    val calc = mock[MediumIDCalculator]
    val rootPath = Paths.get("root")
    val uri = "test://SomeMediumURI"
    val content = List(fileData(rootPath resolve "test.mp3", 1))
    val mediumID = MediumID("a path", None)
    val scanResult = mock[MediaScanResult]
    val data = MediumIDData("Test-Medium-ID", mediumID, scanResult, Map("someFileURI" ->
      fileData(rootPath resolve "file.mp3", 32)))
    when(calc.calculateMediumID(rootPath, mediumID, scanResult, content)).thenReturn(data)

    val actor = calculatorActor(calc)
    actor ! CalculateMediumID(rootPath, mediumID, scanResult, content)
    expectMsg(data)
  }
}
