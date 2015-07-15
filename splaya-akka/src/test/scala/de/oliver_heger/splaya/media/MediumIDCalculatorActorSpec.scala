package de.oliver_heger.splaya.media

import java.nio.file.Paths

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import de.oliver_heger.splaya.media.MediumIDCalculatorActor.CalculateMediumID
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
    val content = List(MediaFile(rootPath resolve "test.mp3", 1))
    val data = MediumIDData("Test-Medium-ID", uri, Map("someFileURI" -> MediaFile(rootPath
      resolve "file.mp3", 32)))
    when(calc.calculateMediumID(rootPath, uri, content)).thenReturn(data)

    val actor = calculatorActor(calc)
    actor ! CalculateMediumID(rootPath, uri, content)
    expectMsg(data)
  }
}
