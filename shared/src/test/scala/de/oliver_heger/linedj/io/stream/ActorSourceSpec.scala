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

package de.oliver_heger.linedj.io.stream

import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.AskTimeoutException
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, DelayOverflowStrategy}
import akka.testkit.TestKit
import akka.util.Timeout
import de.oliver_heger.linedj.io.stream.ActorSource.{ActorCompletionResult, ActorDataResult,
ActorErrorResult}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/**
  * Test class for ''ActorSource''.
  */
class ActorSourceSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike with
  BeforeAndAfterAll with Matchers {
  def this() = this(ActorSystem("ActorSourceSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Creates a test source based on a data actor sending the provided test
    * data.
    *
    * @param data the list with data to be sent by the test data actor
    * @return the source
    */
  private def createSource(data: List[String]): Source[String, Any] = {
    implicit val ec = system.dispatcher
    val actor = system.actorOf(Props(classOf[DataActor], data))
    ActorSource[String](actor, Request)(resultFunc())
  }

  /**
    * Returns the result function that maps responses from the test data
    * actor to result objects.
    *
    * @return the result function
    */
  private def resultFunc(): ActorSource.ResultFunc[String] = {
    case s: String => ActorDataResult(s)
    case Completed => ActorCompletionResult()
    case Error(s) => ActorErrorResult(new Exception(s))
  }

  /**
    * Runs a stream with the specified test source.
    *
    * @param source the test source
    * @return a future with the stream result
    */
  private def runStream(source: Source[String, Any]): Future[List[String]] = {
    implicit val mat = ActorMaterializer()
    val sink = Sink.fold[List[String], String](List.empty[String])((lst, s) => s :: lst)
    source.runWith(sink)
  }

  /**
    * Obtains the result from stream processing.
    *
    * @param f the future with the result
    * @return the result
    */
  private def streamResult(f: Future[List[String]]): List[String] =
    Await.result(f, 3.seconds).reverse

  "An ActorSource" should "return expected data" in {
    val data = List("This", "is", "test", "data", "for", "the", "ActorSource")

    val result = streamResult(runStream(createSource(data)))
    result should be(data)
  }

  it should "handle a slower source" in {
    val data = List("Test", "data", "from", "a", "slower", "test", "source", "x", "y", "z")
    val source = createSource(data).delay(50.millis, DelayOverflowStrategy.backpressure)

    val result = streamResult(runStream(source))
    result should be(data)
  }

  it should "handle a slower actor response" in {
    val data = List("Test", "data", DataActor.Delay, "with", DataActor.Delay, "delay")

    val result = streamResult(runStream(createSource(data)))
    result should be(data)
  }

  it should "handle an error response" in {
    val ErrMsg = "Failed processing!"
    val data = List("Stream", "that", "fails", DataActor.ErrorPrefix + ErrMsg)

    val fut = runStream(createSource(data))
    val ex = intercept[Exception] {
      Await.result(fut, 3.seconds)
    }
    ex.getMessage should be(ErrMsg)
  }

  it should "handle a timeout from the data actor" in {
    val data = List("Stream", "with", "timeout", DataActor.Ignore, "foo")
    implicit val ec = system.dispatcher
    val actor = system.actorOf(Props(classOf[DataActor], data))
    val source = ActorSource[String](actor, Request, Timeout(50.millis))(resultFunc())
    val now = System.currentTimeMillis()

    val fut = runStream(source)
    intercept[AskTimeoutException] {
      Await.result(fut, 10.seconds)
    }
    System.currentTimeMillis() - now should be < 5000L
  }
}

/** Message to request data. */
case object Request

/** Message to indicate that the stream is complete. */
case object Completed

/**
  * Message to indicate a processing failure.
  *
  * @param s the error message
  */
case class Error(s: String)

object DataActor {
  /** Prefix of a data item that causes an error message. */
  val ErrorPrefix = "err:"

  /** A data item causing a delay. */
  val Delay = ":delay:"

  /** A data item that causes a timeout because no response is sent. */
  val Ignore = ":ignore:"
}

/**
  * Test actor class providing the data for the test source. The actor returns
  * the single strings from the provided list and sends a completed message
  * afterwards. If a string starts with the prefix ''err:'', an error message
  * is generated.
  *
  * @param data the list with data
  */
class DataActor(data: List[String]) extends Actor {

  import DataActor._

  private var currentData = data

  override def receive: Receive = {
    case Request =>
      val response = currentData match {
        case h :: t =>
          currentData = t
          dataResponse(h)
        case _ => Some(Completed)
      }
      response foreach sender.!
  }

  /**
    * Produces a response message for the given data item. Handles some
    * special items.
    *
    * @param s the item
    * @return the response message
    */
  private def dataResponse(s: String): Option[Any] = {
    if (s startsWith ErrorPrefix) Some(Error(s.substring(ErrorPrefix.length)))
    else {
      if (s == Ignore) None
      else {
        if (s == Delay) {
          Thread.sleep(50)
        }
        Some(s)
      }
    }
  }
}
