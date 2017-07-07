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

package de.oliver_heger.linedj.archivehttp.impl.download

import java.io.IOException
import java.nio.file.{Path, Paths}

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Props, Terminated}
import akka.stream.DelayOverflowStrategy
import akka.stream.scaladsl.Source
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.ByteString
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.io.stream.AbstractStreamProcessingActor
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

/**
  * Test class for ''WriteChunkActor''.
  */
class WriteChunkActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with FileTestHelper {
  def this() = this(ActorSystem("WriteChunkActorSpec"))

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
    val request = WriteChunkActor.WriteRequest(target, source, 42)
    val actor = system.actorOf(Props[WriteChunkActor])

    actor ! request
    val response = expectMsgType[WriteChunkActor.WriteResponse]
    response.request should be(request)
    val content = readDataFile(target)
    content should be(FileTestHelper.TestData)
  }

  "A WriteChunkActor" should "write data to a target file" in {
    val target = createPathInDirectory("writeTest.txt")

    checkWriteOperation(target)
  }

  it should "create the target directory if necessary" in {
    val target = createPathInDirectory("sub").resolve("foo").resolve("bar")
      .resolve("out.txt")

    checkWriteOperation(target)
  }

  it should "stop if the target directory cannot be created" in {
    val actor = system.actorOf(Props(new WriteChunkActor {
      override private[download] def createTargetDirectory(dir: Path): Path =
        throw new IOException("Cannot create directory " + dir)
    }))

    actor ! WriteChunkActor.WriteRequest(Paths.get("invalid", "path"),
      Source.single(ByteString(FileTestHelper.testBytes())), 0)
    expectStopped(actor)
  }

  it should "stop if there is a failure when writing the target file" in {
    val target = createPathInDirectory("failure").resolve("sub")
    val actor = system.actorOf(Props(new WriteChunkActor {
      // override to not create the directory
      override private[download] def createTargetDirectory(dir: Path): Path = dir
    }))

    actor ! WriteChunkActor.WriteRequest(target,
      Source.single(ByteString(FileTestHelper.testBytes())), 0)
    expectStopped(actor)
  }

  it should "support cancellation of a write operation" in {
    val source = createTestSource().delay(1.second, DelayOverflowStrategy.backpressure)
    val target = createPathInDirectory("delayed.txt")
    val actor = TestActorRef[WriteChunkActor](Props[WriteChunkActor])

    actor ! WriteChunkActor.WriteRequest(target, source, 1)
    actor receive AbstractStreamProcessingActor.CancelStreams
    expectMsgType[WriteChunkActor.WriteResponse]
    val content = readDataFile(target)
    content.length should be < FileTestHelper.TestData.length
  }
}
