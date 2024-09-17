/*
 * Copyright 2015-2024 The Developers Team.
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

import de.oliver_heger.linedj.FileTestHelper
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Framing.FramingException
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import spray.json.*
import spray.json.DefaultJsonProtocol.jsonFormat4

import scala.concurrent.Future

object JsonStreamParserSpec extends DefaultJsonProtocol:
  /**
    * A data class used as model for the parser to be tested.
    *
    * @param author the author of the book
    * @param title  the title of the book
    * @param pages  the number of pages
    * @param price  the price
    */
  case class Book(author: String,
                  title: String,
                  pages: Int,
                  price: Double)

  given bookFormat: RootJsonFormat[Book] = jsonFormat4(Book.apply)

  /** A list with test books. */
  private val books = List(
    Book(
      "Douglas Adams",
      "The hitchhiker's guide to the galaxy",
      987,
      17.5
    ),
    Book(
      "Isaac Asimov",
      "Foundation",
      682,
      19.8
    ),
    Book(
      "J. R. R. Tolkien",
      "Lord of the Rings",
      2500,
      23.75
    )
  )

  /**
    * Returns a source that contains the test books as JSON.
    *
    * @return the source with the test books
    */
  private def testBooksSource(): Source[ByteString, Any] =
    val data = ByteString(books.toJson.prettyPrint).grouped(32)
    Source(data.toList)
end JsonStreamParserSpec

/**
  * Test class for [[JsonStreamParser]].
  */
class JsonStreamParserSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers:
  def this() = this(ActorSystem("JsonStreamParserSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import JsonStreamParserSpec.*

  /**
    * Runs a stream with the stream parser and the provided source.
    *
    * @param source the source of the stream
    * @return the result in form of the received list of books
    */
  private def runStream(source: Source[ByteString, Any] = testBooksSource()): Future[List[Book]] =
    val sink = Sink.fold[List[Book], Book](List.empty) { (lst, elem) =>
      elem :: lst
    }
    JsonStreamParser.parseStream(source).runWith(sink).map(_.reverse)

  "JsonStreamParser" should "correctly parse JSON input" in :
    runStream() map { result =>
      result should contain theSameElementsInOrderAs books
    }

  it should "throw an exception for an unexpected JSON structure" in :
    val json =
      """
        |[
        |  { "test": false }
        |]""".stripMargin
    val source = Source.single(ByteString(json))

    recoverToSucceededIf[DeserializationException] {
      runStream(source)
    }

  it should "throw an exception for non-JSON input" in :
    val data = ByteString(FileTestHelper.testBytes())
    val source = Source(data.grouped(128).toList)

    recoverToSucceededIf[FramingException] {
      runStream(source)
    }

  it should "allow configuring the maximum object length" in :
    val source = JsonStreamParser.parseStream[Book, Any](testBooksSource(), maxObjectLength = 16)
    val sink = Sink.ignore

    recoverToSucceededIf[FramingException] {
      source.runWith(sink)
    }
    