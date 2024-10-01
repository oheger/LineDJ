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

package de.oliver_heger.linedj.archive.metadata.persistence

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.union.MetadataProcessingSuccess
import org.apache.pekko.actor.{ActorSystem, Props, Terminated}
import org.apache.pekko.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Path, Paths}

object PersistentMetadataReaderActorSpec:
  /** The name of the test metadata file. */
  private val MetadataTestFile = "/metadata.mdt"

  /** The number of songs in the test metadata file. */
  private val SongCount = 26

  /** A test medium ID. */
  private val TestMedium = MediumID("someMedium", None)

  /** The read chunk size. */
  private val ReadChunkSize = 2048

/**
  * Test class for ''PersistentMetaDataReaderActor''.
  */
class PersistentMetadataReaderActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with FileTestHelper:

  import PersistentMetadataReaderActorSpec._

  def this() = this(ActorSystem("PersistentMetadataReaderActorSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  /**
    * Tests whether a valid metadata file can be read using a specific chunk
    * size.
    *
    * @param chunkSize the chunk size
    */
  private def checkReadFile(chunkSize: Int): Unit =
    val path = Paths.get(getClass.getResource(MetadataTestFile).toURI)
    val helper = new PersistentMetadataReaderTestHelper(chunkSize)

    val metadata = helper.readFile(path).expectMetadata(SongCount)
    helper.expectTerminated()

    metadata.map(_.mediumID) should contain only TestMedium
    val titles = metadata.map(_.metadata.title.get)
    titles should contain allOf("Wish I Had an Angel", "Our Truth", "When A Dead Man Walks",
      "Ever Dream", "Wheels Of Fire", "Within Me", "Fragments Of Faith")
    val artists = metadata.map(_.metadata.artist.get)
    artists should contain allOf("Lacuna Coil", "Nightwish", "Manowar")

  "A PersistentMetadataReaderActor" should "read a file with metadata" in:
    checkReadFile(ReadChunkSize)

  it should "read a file with metadata with a bigger chunk size" in:
    checkReadFile(32768)

  it should "process an empty data file" in:
    val path = createFileReference()
    val helper = new PersistentMetadataReaderTestHelper

    helper.readFile(path).expectTerminated()

  it should "process a non JSON file" in:
    val path = createDataFile()
    val helper = new PersistentMetadataReaderTestHelper

    helper.readFile(path).expectTerminated()

  it should "handle a non-existing file" in:
    val path = Paths.get("non-existing-file.nxt")
    val helper = new PersistentMetadataReaderTestHelper

    helper.readFile(path).expectTerminated()

  it should "create correct Props" in:
    val parent = TestProbe()
    val props = PersistentMetadataReaderActor(parent.ref, ReadChunkSize)

    classOf[PersistentMetadataReaderActor].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should be(List(parent.ref, ReadChunkSize))

  /**
    * A test helper class which manages the required dependencies.
    *
    * @param chunkSize the chunk size when reading data files
    */
  private class PersistentMetadataReaderTestHelper(chunkSize: Int = ReadChunkSize):
    /** A test probe for the parent actor. */
    val parent: TestProbe = TestProbe()

    /** The test actor. */
    val metaReaderActor: TestActorRef[PersistentMetadataReaderActor] =
      TestActorRef[PersistentMetadataReaderActor](createProps())

    /**
      * Sends the test actor a message to read the specified metadata file.
      *
      * @param path the path to the file to be read
      * @return this test helper
      */
    def readFile(path: Path): PersistentMetadataReaderTestHelper =
      metaReaderActor ! PersistentMetadataReaderActor.ReadMetadataFile(path, TestMedium)
      this

    /**
      * Expects the given number of processing result messages sent to the
      * parent actor probe.
      *
      * @param count the number of messages
      * @return a set with the received messages
      */
    def expectMetadata(count: Int): Set[MetadataProcessingSuccess] =
      (1 to count).foldLeft(Set.empty[MetadataProcessingSuccess]) { (s, _) =>
        s + parent.expectMsgType[MetadataProcessingSuccess]
      }

    /**
      * Expects that the test actor has been stopped.
      *
      * @return this test helper
      */
    def expectTerminated(): PersistentMetadataReaderTestHelper =
      parent watch metaReaderActor
      parent.expectMsgType[Terminated]
      this

    /**
      * Creates the properties for the test actor.
      *
      * @return creation properties
      */
    private def createProps(): Props =
      PersistentMetadataReaderActor(parent.ref, chunkSize)

