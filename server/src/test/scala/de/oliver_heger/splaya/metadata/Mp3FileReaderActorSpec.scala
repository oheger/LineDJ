/*
 * Copyright 2015 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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
package de.oliver_heger.splaya.metadata

import java.io.IOException
import java.nio.file.Paths

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import de.oliver_heger.splaya.SupervisionTestActor
import de.oliver_heger.splaya.config.ServerConfig
import de.oliver_heger.splaya.io.{ChannelHandler, FileReaderActor}
import de.oliver_heger.splaya.utils.ChildActorFactory
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

object Mp3FileReaderActorSpec {
  /** The class for file reader actor. */
  private val ClassReaderActor = classOf[FileReaderActor]

  /** The class for ID3 frame reader actor. */
  private val ClassFrameReaderActor = classOf[ID3FrameReaderActor]

  /** The initialization message containing the test path. */
  private val InitFileMessage = ChannelHandler.InitFile(Paths get "TestPath")

  /** Constant for the read chunk size. */
  private val ReadChunkSize = 16384
}

/**
 * Test class for ''Mp3FileReaderActor''.
 */
class Mp3FileReaderActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  import Mp3FileReaderActorSpec._

  def this() = this(ActorSystem("Mp3FileReaderActorSpec"))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
  }

  "A Mp3FileReaderActor" should "start reading a media file" in {
    val helper = new Mp3FileReaderActorTestHelper
    val actor = helper.createTestActor()

    helper expectInitialization actor
  }

  it should "handle a read result" in {
    val helper = new Mp3FileReaderActorTestHelper
    val actor = helper.createTestActor()
    val resultMsg = FileReaderActor.ReadResult(new Array[Byte](128), 64)
    helper expectInitialization actor

    actor ! resultMsg
    helper.frameReaderProbe.expectMsg(FileReaderActor.ReadData(ReadChunkSize))
    helper.probeCollector.expectMsg(ProcessMp3Data(InitFileMessage.path, resultMsg))
  }

  it should "handle an endOfFile message" in {
    val helper = new Mp3FileReaderActorTestHelper
    val actor = helper.createTestActor()
    helper expectInitialization actor

    actor ! FileReaderActor.EndOfFile(InitFileMessage.path)
    helper.probeCollector.expectMsg(MediaFileRead(InitFileMessage.path))
  }

  it should "ignore an endOfFile message for a different path" in {
    val helper = new Mp3FileReaderActorTestHelper
    val actor = helper.createTestActor()
    helper expectInitialization actor

    actor ! FileReaderActor.EndOfFile(null)
    actor ! FileReaderActor.EndOfFile(InitFileMessage.path)
    helper.probeCollector.expectMsg(MediaFileRead(InitFileMessage.path))
    val ping = "ping"
    helper.probeCollector.ref ! ping
    helper.probeCollector.expectMsg(ping) // expect no further messages
  }

  it should "escalate exceptions of child actors to its parent" in {
    val supervisionStrategy = OneForOneStrategy() {
      case _: IOException => Stop
    }
    val helper = new Mp3FileReaderActorTestHelper
    val supervisor = SupervisionTestActor(system, supervisionStrategy, Mp3FileReaderActor(helper
      .extractionContext))
    val probe = TestProbe()
    val actor = supervisor.underlyingActor.childActor
    probe watch actor

    actor ! ReadMediaFile(InitFileMessage.path)
    val termMsg = probe.expectMsgType[Terminated]
    termMsg.actor should be(actor)
  }

  /**
   * A test helper class collecting a number of objects needed by multiple
   * test cases.
   */
  private class Mp3FileReaderActorTestHelper {
    /** A probe for the underlying reader actor. */
    val readerProbe = TestProbe()

    /** A probe for the ID3 frame reader actor. */
    val frameReaderProbe = TestProbe()

    /** A probe for the collector actor. */
    val probeCollector = TestProbe()

    /** The extraction context. */
    val extractionContext = MetaDataExtractionContext(probeCollector.ref, createConfig())

    /**
     * Creates a test actor that uses a special child actor factory which allows
     * injecting test probes.
     * @return the test actor reference
     */
    def createTestActor(): ActorRef = {
      val props = Props(new Mp3FileReaderActor(extractionContext) with ChildActorFactory {
        override def createChildActor(p: Props): ActorRef = {
          p.actorClass() match {
            case ClassReaderActor =>
              p.args shouldBe 'empty
              readerProbe.ref

            case ClassFrameReaderActor =>
              p.args should have length 2
              p.args.head should be(readerProbe.ref)
              p.args(1) should be(extractionContext)
              frameReaderProbe.ref
          }
        }
      })
      system.actorOf(props)
    }

    /**
     * Checks the correct initialization of the test actor.
     * @param actor the test actor
     */
    def expectInitialization(actor: ActorRef): Unit = {
      actor ! ReadMediaFile(InitFileMessage.path)
      frameReaderProbe.expectMsg(InitFileMessage)
      frameReaderProbe.expectMsg(FileReaderActor.ReadData(ReadChunkSize))
    }

    /**
     * Creates an initialized mock for the server configuration.
     * @return the configuration mock
     */
    private def createConfig(): ServerConfig = {
      val config = mock[ServerConfig]
      when(config.metaDataReadChunkSize).thenReturn(ReadChunkSize)
      config
    }
  }

}
