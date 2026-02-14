/*
 * Copyright 2015-2026 The Developers Team.
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

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import spray.json.*
import spray.json.DefaultJsonProtocol.*

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future

object ListSeparatorStageSpec:
  /**
    * A simple data class used for testing JSON serialization.
    *
    * @param name the name of the person
    * @param age  the age
    */
  case class Person(name: String, age: Int)

  given personFormat: RootJsonFormat[Person] = jsonFormat2(Person.apply)

  /**
    * Returns a [[Sink]] that collects the string output of a stream.
    *
    * @return the [[Sink]] returning the resulting [[ByteString]]
    */
  private def foldSink: Sink[ByteString, Future[ByteString]] =
    Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
end ListSeparatorStageSpec

/**
  * Test class for ''ListSeparatorStage''.
  */
class ListSeparatorStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers:
  def this() = this(ActorSystem("ListSeparatorStageSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  import ListSeparatorStageSpec.*

  /**
    * Executes a test stream with the specified elements and returns the
    * resulting string.
    *
    * @param data the list with stream elements
    * @param sep  a separator string
    * @return the result of stream processing
    */
  private def runStream(data: List[String], sep: => String = ", "): Future[String] =
    val source = Source(data)
    val sink = foldSink
    val futResult = source.via(
      new ListSeparatorStage[String]("(", sep, ")")((s, i) => s + i)).runWith(sink)
    futResult.map(_.utf8String)

  "A ListSeparatorStage" should "produce a valid output with a list of elements" in :
    runStream(List("A", "B", "C")) map : result =>
      result should be("(A0, B1, C2)")

  it should "handle an empty stream correctly" in :
    runStream(List()) map : result =>
      result should be("()")

  it should "resolve the separator only once" in :
    val counter = new AtomicInteger
    runStream(List("a", "b", "c"), "separator_" + counter.incrementAndGet()) map : _ =>
      counter.get() should be(1)

  "A JSON stage" should "produce correct JSON output" in :
    val persons = List(
      Person("Anton", 28),
      Person("Bertie", 29),
      Person("Connie", 30),
      Person("Det", 31),
      Person("Edi", 32),
      Person("Fritzie", 33)
    )
    val source = Source(persons)
    val stage = ListSeparatorStage.jsonStage[Person]

    source.via(stage).runWith(foldSink).map: result =>
      val jsonAst = result.utf8String.parseJson
      val deserializedPersons = jsonAst.convertTo[List[Person]]
      deserializedPersons should contain theSameElementsInOrderAs persons
