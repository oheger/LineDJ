/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.temp

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.io.RemoveFileActor
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.testkit.{TestActorRef, TestKit, TestProbe}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.{Files, Path, Paths}

object RemoveTempFilesActorSpec:
  /** The name to be used for the blocking dispatcher. */
  private val DispatcherName = "blockingDispatcher"

/**
  * Test class for ''RemoveTempFilesActor''.
  */
class RemoveTempFilesActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with FileTestHelper:

  import RemoveTempFilesActorSpec._

  def this() = this(ActorSystem("RemoveTempFilesActorSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    tearDownTestFile()

  /**
    * Creates a mock for a path generator that accepts the specified paths as
    * removable.
    *
    * @param acceptedPaths the paths that can be removed
    * @return the mock path generator
    */
  private def createPathGeneratorMock(acceptedPaths: Path*): TempPathGenerator =
    val gen = mock[TempPathGenerator]
    when(gen.isRemovableTempPath(any())).thenReturn(false)
    acceptedPaths foreach { p =>
      when(gen.isRemovableTempPath(p)).thenReturn(true)
    }
    gen

  /**
    * Creates a directory for temporary download files.
    *
    * @param name the name of the directory
    * @return the newly created directory
    */
  private def createTempDirectory(name: String): Path =
    Files.createDirectory(createPathInDirectory(name))

  "A RemoveTempFilesActor" should "create correct Props" in:
    val props = RemoveTempFilesActor(DispatcherName)

    classOf[RemoveTempFilesActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should be(List(DispatcherName))

  it should "pass files to be removed to the child actor" in:
    val Path1 = Paths get "tempFile1.tmp"
    val Path2 = Paths get "tempFile2.tmp"
    val Path3 = Paths get "tempFile3.tmp"
    val helper = new RemoveActorTestHelper

    helper.send(RemoveTempFilesActor.RemoveTempFiles(List(Path1, Path2, Path3)))
      .expectRemoveRequestFor(Path1)
      .expectRemoveRequestFor(Path2)
      .expectRemoveRequestFor(Path3)

  it should "handle confirmation messages for removed files" in:
    val helper = new RemoveActorTestHelper

    helper send RemoveFileActor.FileRemoved(Paths get "somePath")

  it should "clean the temporary download directory" in:
    val dir1 = createTempDirectory("downloadDir1")
    val dir2 = createTempDirectory("downloadDir2")
    val dir3 = createTempDirectory("anotherDir")
    val file1 = writeFileContent(dir1.resolve("downloadFile1.tmp"), FileTestHelper.TestData)
    val file2 = writeFileContent(dir1.resolve("downloadFile2.tmp"), "other data")
    writeFileContent(dir1.resolve("noDownloadFile.txt"), "foo")
    val file4 = writeFileContent(dir2.resolve("downloadFile3.tmp"), "more downloads")
    val file5 = writeFileContent(dir3.resolve("README.TXT"), "important data")
    val generator = createPathGeneratorMock(dir1, dir2, file1, file2, file4)
    val helper = new RemoveActorTestHelper

    helper.send(RemoveTempFilesActor.ClearTempDirectory(testDirectory, generator))
    val removedFiles = helper.expectRemoveRequests(3)
    val removedDirs = helper.expectRemoveRequests(2)
    removedFiles should contain only(file1, file2, file4)
    removedDirs should contain only(dir1, dir2)
    verify(generator, never()).isRemovableTempPath(file5)

  /**
    * Test helper class that manages a test instance and its dependencies.
    */
  private class RemoveActorTestHelper:
    /** Test probe for the remove child actor. */
    private val removeFileActor = TestProbe()

    /** A test actor instance. */
    private val removeTempActor = createTestActor()

    /**
      * Sends the specified message to the test actor.
      *
      * @param msg the message to be sent
      * @return this test helper
      */
    def send(msg: Any): RemoveActorTestHelper =
      removeTempActor receive msg
      this

    /**
      * Checks whether the remove file actor was sent a request to remove the
      * specified path.
      *
      * @param path the path
      * @return this test helper
      */
    def expectRemoveRequestFor(path: Path): RemoveActorTestHelper =
      removeFileActor.expectMsg(RemoveFileActor.RemoveFile(path))
      this

    /**
      * Expects a given number of requests to remove temporary paths. The paths
      * of the files to be removed are returned as set.
      *
      * @param count the number of expected remove operations
      * @return a set with the paths passed to the remove actor
      */
    def expectRemoveRequests(count: Int): Set[Path] =
      (1 to count)
        .map(_ => removeFileActor.expectMsgType[RemoveFileActor.RemoveFile].path)
        .toSet

    /**
      * Creates a test actor instance.
      *
      * @return the test actor instance
      */
    private def createTestActor(): TestActorRef[RemoveTempFilesActor] =
      TestActorRef(Props(new RemoveTempFilesActor(DispatcherName) with ChildActorFactory {
        override def createChildActor(p: Props): ActorRef = {
          p.actorClass() should be(classOf[RemoveFileActor])
          p.args shouldBe empty
          p.dispatcher should be(DispatcherName)
          removeFileActor.ref
        }
      }))

