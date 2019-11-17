/*
 * Copyright 2015-2019 The Developers Team.
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

package de.oliver_heger.linedj.io.parser

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, FlowShape, Graph}
import akka.testkit.TestKit
import akka.util.ByteString
import de.oliver_heger.linedj.io.parser.ParserStage.ChunkSequenceParser
import de.oliver_heger.linedj.io.parser.ParserTypes.Failure
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.collection.immutable.Seq
import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Test class for ''ParserStage''.
  */
class ParserStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike with
  BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("ParserStageSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Runs a flow with the given source and parser function and returns the
    * sequence of the produced result objects.
    *
    * @param parseFunc the parser function
    * @param source    the source
    * @return the results of stream processing
    */
  private def runFlow(parseFunc: ChunkSequenceParser[String], source: Source[ByteString,
    NotUsed]): Seq[String] = {
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    val stage: Graph[FlowShape[ByteString, String], NotUsed] =
      new ParserStage[String](parseFunc)
    val sink = Sink.seq[String]
    val flow = source.via(stage).toMat(sink)(Keep.right)
    val results = Await.result(flow.run(), 5.seconds)
    results
  }

  "A ParserStage" should "produce correct parsing results" in {
    val parseFunc = mock[ChunkSequenceParser[String]]
    val chunk1 = ByteString("Chunk1")
    val chunk2 = ByteString("Chunk2")
    val chunk3 = ByteString("Chunk3")
    val failure1 = Some(Failure(ParseError(), isCommitted = false, List(1)))
    val failure2 = Some(Failure(ParseError(), isCommitted = false, List(2)))
    when(parseFunc.apply(chunk1, None, false)).thenReturn((List("r1", "r2"), failure1))
    when(parseFunc.apply(chunk2, failure1, false)).thenReturn((List("r3"), failure2))
    when(parseFunc.apply(chunk3, failure2, true)).thenReturn((List("r4", "r5", "r6"), None))

    val source = Source(List(chunk1, chunk2, chunk3))
    val results = runFlow(parseFunc, source)
    results should be(List("r1", "r2", "r3", "r4", "r5", "r6"))
  }

  it should "handle an empty stream correctly" in {
    val source = Source.empty[ByteString]
    val parseFunc = mock[ChunkSequenceParser[String]]

    runFlow(parseFunc, source) should have size 0
    verifyZeroInteractions(parseFunc)
  }
}
