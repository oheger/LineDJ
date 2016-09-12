package de.oliver_heger.linedj.archive.media

import java.nio.file.Paths

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.archive.media.MediumIDCalculatorActor.CalculateMediumID
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

/**
 * Test class for ''MediumIDCalculatorActor''.
 */
class MediumIDCalculatorActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {
  def this() = this(ActorSystem("MediumIDCalculatorActorSpec"))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
  }

  /**
   * Creates a test actor instance with the passed in calculator.
   * @param calc the calculator to be passed to the actor
   * @return the reference to the test actor
   */
  private def calculatorActor(calc: MediumIDCalculator): ActorRef =
    system.actorOf(Props(classOf[MediumIDCalculatorActor], calc))

  "A MediumCalculatorActor" should "calculate a medium ID" in {
    val calc = mock[MediumIDCalculator]
    val rootPath = Paths.get("root")
    val uri = "test://SomeMediumURI"
    val content = List(FileData(rootPath resolve "test.mp3", 1))
    val mediumID = MediumID("a path", None)
    val scanResult = mock[MediaScanResult]
    val data = MediumIDData("Test-Medium-ID", mediumID, scanResult, Map("someFileURI" ->
      FileData(rootPath resolve "file.mp3", 32)))
    when(calc.calculateMediumID(rootPath, mediumID, scanResult, content)).thenReturn(data)

    val actor = calculatorActor(calc)
    actor ! CalculateMediumID(rootPath, mediumID, scanResult, content)
    expectMsg(data)
  }
}
