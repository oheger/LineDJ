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

package de.oliver_heger.linedj.io.stream

import java.io.IOException
import java.nio.file.{Path, Paths}

import akka.NotUsed
import akka.actor.{ActorLogging, ActorRef, ActorSystem, Props, Terminated}
import akka.stream.DelayOverflowStrategy
import akka.stream.scaladsl.Source
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.ByteString
import de.oliver_heger.linedj.FileTestHelper
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

/**
  * Test class for ''AbstractFileWriterActor''.
  */
class AbstractFileWriterActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with FileTestHelper {
  def this() = this(ActorSystem("AbstractFileWriterActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    tearDownTestFile()
  }

  /**
    * Expects that the specified actor has been stopped.
    *
    * @param actor the actor to be checked
    */
  private def expectStopped(actor: ActorRef): Unit = {
    val watcher = TestProbe()
    watcher watch actor
    watcher.expectMsgType[Terminated]
  }

  /**
    * Creates a source with test data.
    *
    * @return the test source
    */
  private def createTestSource(): Source[ByteString, NotUsed] =
    Source[ByteString](ByteString(FileTestHelper.testBytes()).grouped(16).toList)

  /**
    * Checks whether data can be written to a target file.
    *
    * @param target the target file
    */
  private def checkWriteOperation(target: Path): Unit = {
    val source = createTestSource()
    val request = WriteRequest(target = target, source = source)
    val actor = system.actorOf(Props[FileWriterActorTestImpl])

    actor ! request
    val response = expectMsgType[FileWritten]
    response.target should be(request.target)
    val content = readDataFile(target)
    content should be(FileTestHelper.TestData)
  }

  "A FileWriterActor" should "write data to a target file" in {
    val target = createPathInDirectory("writeTest.txt")

    checkWriteOperation(target)
  }

  it should "create the target directory if necessary" in {
    val target = createPathInDirectory("sub").resolve("foo").resolve("bar")
      .resolve("out.txt")

    checkWriteOperation(target)
  }

  it should "stop if the target directory cannot be created" in {
    val actor = system.actorOf(Props(new FileWriterActorTestImpl {
      override private[stream] def createTargetDirectory(dir: Path): Path =
        throw new IOException("Cannot create directory " + dir)
    }))

    actor ! WriteRequest(Source.single(ByteString(FileTestHelper.testBytes())),
      Paths.get("invalid", "path"))
    expectStopped(actor)
  }

  it should "stop if there is a failure when writing the target file" in {
    val target = createPathInDirectory("failure").resolve("sub")
    val actor = system.actorOf(Props(new FileWriterActorTestImpl {
      // override to not create the directory
      override private[stream] def createTargetDirectory(dir: Path): Path = dir
    }))

    actor ! WriteRequest(Source.single(ByteString(FileTestHelper.testBytes())), target)
    expectStopped(actor)
  }

  it should "support cancellation of a write operation" in {
    val source = createTestSource().delay(1.second, DelayOverflowStrategy.backpressure)
    val target = createPathInDirectory("delayed.txt")
    val actor = TestActorRef[FileWriterActorTestImpl](Props[FileWriterActorTestImpl])

    actor ! WriteRequest(source, target)
    actor receive AbstractStreamProcessingActor.CancelStreams
    expectMsgType[FileWritten]
    val content = readDataFile(target)
    content.length should be < FileTestHelper.TestData.length
  }

  it should "allow overriding the error handling function for write errors" in {
    val target = createPathInDirectory("customFailureHandling").resolve("sub")
    val actor = system.actorOf(Props(new FileWriterActorTestImpl {
      // override to not create the directory
      override private[stream] def createTargetDirectory(dir: Path): Path = dir

      override protected def handleFailure(client: ActorRef, e: Throwable): Unit = {
        client ! e
      }
    }))

    actor ! WriteRequest(Source.single(ByteString(FileTestHelper.testBytes())), target)
    expectMsgType[IOException]
  }

  it should "allow overriding the error handling function for create dir errors" in {
    val exception = new IOException("Cannot create directory!")
    val actor = system.actorOf(Props(new FileWriterActorTestImpl {
      override private[stream] def createTargetDirectory(dir: Path): Path = {
        throw exception
      }

      override protected def handleFailure(client: ActorRef, e: Throwable): Unit = {
        client ! e
      }
    }))

    actor ! WriteRequest(Source.single(ByteString(FileTestHelper.testBytes())),
      Paths.get("invalid", "path"))
    expectMsg(exception)
  }
}

/**
  * Message class for sending write requests.
  *
  * @param source the data source
  * @param target the target path
  */
private case class WriteRequest(source: Source[ByteString, Any], target: Path)

/**
  * Message class used as response for a write request.
  *
  * @param target the target file that was written
  */
private case class FileWritten(target: Path)

/**
  * A test actor implementation that triggers the write operation.
  */
class FileWriterActorTestImpl extends AbstractFileWriterActor with CancelableStreamSupport with
  ActorLogging {

  /**
    * The custom receive function. Here derived classes can provide their own
    * message handling.
    *
    * @return the custom receive method
    */
  override protected def customReceive: Receive = {
    case req: WriteRequest =>
      writeFile(req.source, req.target, FileWritten(req.target))
  }
}
