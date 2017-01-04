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

package de.oliver_heger.linedj.archive.metadata.persistence.parser

import java.nio.file.Paths

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{FileIO, Flow, Keep, Sink}
import akka.stream.{ActorMaterializer, FlowShape, Graph}
import akka.util.ByteString
import de.oliver_heger.linedj.archive.metadata.MetaDataProcessingResult
import de.oliver_heger.linedj.shared.archive.media.MediumID

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Test class for the parser stage.
  */
object StreamTest {
  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("streamTest")
    implicit val materializer = ActorMaterializer()

    val parser = new MetaDataParser(ParserImpl, JSONParser.jsonParser(ParserImpl))
    val mediumID = MediumID("testMedium", None)
    val stage: Graph[FlowShape[ByteString, MetaDataProcessingResult], NotUsed] =
      new ParserStage(parser, mediumID)
    val parseFlow = Flow.fromGraph(stage)
    val sink = Sink.fold[List[MetaDataProcessingResult],
      MetaDataProcessingResult](List.empty[MetaDataProcessingResult])((l, e) => e :: l)
    val source = FileIO.fromPath(Paths.get(args.head))
    val flow = source.via(parseFlow).toMat(sink)(Keep.right)
    val future = flow.run()
    val results = Await.result(future, 1.minute)
    results.reverse foreach println
    println(s"Got ${results.size} results.")
    system.terminate()
  }
}
