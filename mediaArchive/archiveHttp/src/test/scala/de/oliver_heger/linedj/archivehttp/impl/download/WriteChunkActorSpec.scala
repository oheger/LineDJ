/*
 * Copyright 2015-2023 The Developers Team.
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

import de.oliver_heger.linedj.FileTestHelper
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.{ActorSystem, Props}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.testkit.{ImplicitSender, TestKit}
import org.apache.pekko.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''WriteChunkActor''.
  */
class WriteChunkActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with FileTestHelper:
  def this() = this(ActorSystem("WriteChunkActorSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    tearDownTestFile()

  /**
    * Creates a source with test data.
    *
    * @return the test source
    */
  private def createTestSource(): Source[ByteString, NotUsed] =
    Source[ByteString](ByteString(FileTestHelper.testBytes()).grouped(16).toList)

  "A WriteChunkActor" should "write data to a target file" in:
    val target = createPathInDirectory("writeTest.txt")
    val source = createTestSource()
    val request = WriteChunkActor.WriteRequest(target, source, 42)
    val actor = system.actorOf(Props[WriteChunkActor]())

    actor ! request
    val response = expectMsgType[WriteChunkActor.WriteResponse]
    response.request should be(request)
    val content = readDataFile(target)
    content should be(FileTestHelper.TestData)
