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

package de.oliver_heger.linedj.archive.metadata.persistence

import java.nio.file.{Path, Paths}

import akka.actor.{ActorSystem, Props, Terminated}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.union.MetaDataProcessingResult
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

object PersistentMetaDataReaderActorSpec {
  /** The name of the test meta data file. */
  private val MetaDataTestFile = "/metadata.mdt"

  /** The number of songs in the test meta data file. */
  private val SongCount = 26

  /** A test medium ID. */
  private val TestMedium = MediumID("someMedium", None)

  /** The read chunk size. */
  private val ReadChunkSize = 2048
}

/**
  * Test class for ''PersistentMetaDataReaderActor''.
  */
class PersistentMetaDataReaderActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers
  with FileTestHelper {

  import PersistentMetaDataReaderActorSpec._

  def this() = this(ActorSystem("PersistentMetaDataReaderActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Tests whether a valid meta data file can be read using a specific chunk
    * size.
    *
    * @param chunkSize the chunk size
    */
  private def checkReadFile(chunkSize: Int): Unit = {
    val path = Paths.get(getClass.getResource(MetaDataTestFile).toURI)
    val helper = new PersistentMetaDataReaderTestHelper(chunkSize)

    val metaData = helper.readFile(path).expectMetaData(SongCount)
    helper.expectTerminated()

    metaData.map(_.mediumID) should contain only TestMedium
    val titles = metaData.map(_.metaData.title.get)
    titles should contain allOf("Wish I Had an Angel", "Our Truth", "When A Dead Man Walks",
      "Ever Dream", "Wheels Of Fire", "Within Me", "Fragments Of Faith")
    val artists = metaData.map(_.metaData.artist.get)
    artists should contain allOf("Lacuna Coil", "Nightwish", "Manowar")
  }

  "A PersistentMetaDataReaderActor" should "read a file with meta data" in {
    checkReadFile(ReadChunkSize)
  }

  it should "read a file with meta data with a bigger chunk size" in {
    checkReadFile(32768)
  }

  it should "process an empty data file" in {
    val path = createFileReference()
    val helper = new PersistentMetaDataReaderTestHelper

    helper.readFile(path).expectTerminated()
  }

  it should "process a non JSON file" in {
    val path = createDataFile()
    val helper = new PersistentMetaDataReaderTestHelper

    helper.readFile(path).expectTerminated()
  }

  it should "handle a non-existing file" in {
    val path = Paths.get("non-existing-file.nxt")
    val helper = new PersistentMetaDataReaderTestHelper

    helper.readFile(path).expectTerminated()
  }

  it should "create correct Props" in {
    val parent = TestProbe()
    val props = PersistentMetaDataReaderActor(parent.ref, ReadChunkSize)

    classOf[PersistentMetaDataReaderActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should be(List(parent.ref, ReadChunkSize))
  }

  /**
    * A test helper class which manages the required dependencies.
    *
    * @param chunkSize the chunk size when reading data files
    */
  private class PersistentMetaDataReaderTestHelper(chunkSize: Int = ReadChunkSize) {
    /** A test probe for the parent actor. */
    val parent = TestProbe()

    /** The test actor. */
    val metaReaderActor: TestActorRef[PersistentMetaDataReaderActor] =
      TestActorRef[PersistentMetaDataReaderActor](createProps())

    /**
      * Sends the test actor a message to read the specified meta data file.
      *
      * @param path the path to the file to be read
      * @return this test helper
      */
    def readFile(path: Path): PersistentMetaDataReaderTestHelper = {
      metaReaderActor ! PersistentMetaDataReaderActor.ReadMetaDataFile(path, TestMedium)
      this
    }

    /**
      * Expects the given number of processing result messages sent to the
      * parent actor probe.
      *
      * @param count the number of messages
      * @return a set with the received messages
      */
    def expectMetaData(count: Int): Set[MetaDataProcessingResult] = {
      (1 to count).foldLeft(Set.empty[MetaDataProcessingResult]) { (s, _) =>
        s + parent.expectMsgType[MetaDataProcessingResult]
      }
    }

    /**
      * Expects that the test actor has been stopped.
      *
      * @return this test helper
      */
    def expectTerminated(): PersistentMetaDataReaderTestHelper = {
      parent watch metaReaderActor
      parent.expectMsgType[Terminated]
      this
    }

    /**
      * Creates the properties for the test actor.
      *
      * @return creation properties
      */
    private def createProps(): Props = {
      PersistentMetaDataReaderActor(parent.ref, chunkSize)
    }
  }

}
