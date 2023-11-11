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

package de.oliver_heger.linedj.playlist.persistence

import de.oliver_heger.linedj.test.FileTestHelper
import org.apache.pekko.actor.{ActorSystem, Props}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.testkit.{ImplicitSender, TestKit}
import org.apache.pekko.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

/**
  * Test class for ''PlaylistFileWriterActor''.
  */
class PlaylistFileWriterActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with FileTestHelper:
  def this() = this(ActorSystem("PlaylistFileWriterActorSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    tearDownTestFile()

  "A PlaylistFileWriterActor" should "write a file" in:
    val target = createPathInDirectory("test.dat")
    val content = FileTestHelper.testBytes().grouped(32).map(a => ByteString(a))
    val src = Source[ByteString](content.toList)
    val writer = system.actorOf(Props[PlaylistFileWriterActor]())

    writer ! PlaylistFileWriterActor.WriteFile(src, target)
    expectMsg(PlaylistFileWriterActor.FileWritten(target, None))
    readDataFile(target) should be(FileTestHelper.TestData)

  it should "handle an error when writing a file gracefully" in:
    val target = createPathInDirectory("error")
    Files createDirectory target
    val source = Source.single(ByteString("This will not work"))
    val writer = system.actorOf(Props[PlaylistFileWriterActor]())

    writer ! PlaylistFileWriterActor.WriteFile(source, target)
    val msg = expectMsgType[PlaylistFileWriterActor.FileWritten]
    msg.target should be(target)
    msg.exception.isDefined shouldBe true

    val target2 = createPathInDirectory("out.txt")
    val source2 = Source.single(ByteString(FileTestHelper.TestData))
    writer ! PlaylistFileWriterActor.WriteFile(source2, target2)
    expectMsg(PlaylistFileWriterActor.FileWritten(target2, None))
