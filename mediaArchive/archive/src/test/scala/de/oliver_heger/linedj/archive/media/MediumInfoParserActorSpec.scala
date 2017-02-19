/*
 * Copyright 2015-2017 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.oliver_heger.linedj.archive.media

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import de.oliver_heger.linedj.archive.media.MediumInfoParserActor.ParseMediumInfo
import de.oliver_heger.linedj.shared.archive.media.{MediumID, MediumInfo}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

object MediumInfoParserActorSpec {
  /** Constant for a medium ID. */
  private val TestMediumID = MediumID("test://TestMedium", None)
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
    val mediumSettingsData = MediumInfo(name = "TestMedium", description = "Some desc",
      mediumID = TestMediumID, orderMode = "Directories", orderParams = "", checksum = "")
    val data = new Array[Byte](16)
    when(parser.parseMediumInfo(data, TestMediumID)).thenReturn(Some(mediumSettingsData))

    val actor = parserActor(parser)
    actor ! ParseMediumInfo(data, TestMediumID)
    expectMsg(mediumSettingsData)
  }

  it should "return a default description if a parsing error occurs" in {
    val parser = mock[MediumInfoParser]
    val data = new Array[Byte](16)
    when(parser.parseMediumInfo(data, TestMediumID)).thenReturn(None)

    val actor = parserActor(parser)
    actor ! ParseMediumInfo(data, TestMediumID)
    val settings = expectMsgType[MediumInfo]
    settings.name should be("unknown")
    settings.description should have length 0
    settings.mediumID should be(TestMediumID)
    settings.orderMode should have length 0
    settings.orderParams should have length 0
    settings.checksum should have length 0
  }
}
