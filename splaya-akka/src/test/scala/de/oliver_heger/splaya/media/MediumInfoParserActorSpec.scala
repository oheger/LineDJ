package de.oliver_heger.splaya.media

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import de.oliver_heger.splaya.media.MediumInfoParserActor.ParseMediumInfo
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

object MediumInfoParserActorSpec {
  /** Constant for a medium URI. */
  private val MediumURI = "test://TestMedium"
}

/**
 * Test class for ''MediumInfoParserActor''.
 */
class MediumInfoParserActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  import MediumInfoParserActorSpec._

  def this() = this(ActorSystem("MediumInfoParserActorSpec"))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
  }

  /**
   * Creates a test actor which operates on the specified parser.
   * @param parser the parser
   * @return the test actor reference
   */
  private def parserActor(parser: MediumInfoParser): ActorRef =
    system.actorOf(Props(classOf[MediumInfoParserActor], parser))

  "A MediumInfoParserActor" should "handle a successful parse operation" in {
    val parser = mock[MediumInfoParser]
    val mediumSettingsData = MediumSettingsData(name = "TestMedium", description = "Some desc",
      mediumURI = MediumURI, orderMode = "Directories",
      orderParams = xml.NodeSeq.Empty)
    val data = new Array[Byte](16)
    when(parser.parseMediumInfo(data, MediumURI)).thenReturn(Some(mediumSettingsData))

    val actor = parserActor(parser)
    actor ! ParseMediumInfo(data, MediumURI)
    expectMsg(mediumSettingsData)
  }

  it should "return a default description if a parsing error occurs" in {
    val parser = mock[MediumInfoParser]
    val data = new Array[Byte](16)
    when(parser.parseMediumInfo(data, MediumURI)).thenReturn(None)

    val actor = parserActor(parser)
    actor ! ParseMediumInfo(data, MediumURI)
    val settings = expectMsgType[MediumSettingsData]
    settings.name should be("unknown")
    settings.description should have length 0
    settings.mediumURI should be(MediumURI)
    settings.orderMode should have length 0
    settings.orderParams should have length 0
  }
}
