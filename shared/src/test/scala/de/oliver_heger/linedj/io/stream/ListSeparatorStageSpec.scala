/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.io.stream

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestKit
import akka.util.ByteString
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Test class for ''ListSeparatorStage''.
  */
class ListSeparatorStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  FlatSpecLike with BeforeAndAfterAll with Matchers {
  def this() = this(ActorSystem("ListSeparatorStageSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Executes a test stream with the specified elements and returns the
    * resulting string.
    *
    * @param data the list with stream elements
    * @param sep  a separator string
    * @return the result of stream processing
    */
  private def runStream(data: List[String], sep: => String = ", "): String = {
    val source = Source(data)
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    val futResult = source.via(
      new ListSeparatorStage[String]("(", sep, ")")((s, i) => s + i)).runWith(sink)
    Await.result(futResult, 3.seconds).utf8String
  }

  "A ListSeparatorStage" should "produce a valid output with a list of elements" in {
    val result = runStream(List("A", "B", "C"))

    result should be("(A0, B1, C2)")
  }

  it should "handle an empty stream correctly" in {
    val result = runStream(List())

    result should be("()")
  }

  it should "resolve the separator only once" in {
    val counter = new AtomicInteger
    runStream(List("a", "b", "c"), "separator_" + counter.incrementAndGet())

    counter.get() should be(1)
  }
}
