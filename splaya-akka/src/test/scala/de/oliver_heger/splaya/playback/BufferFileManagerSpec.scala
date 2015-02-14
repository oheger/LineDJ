package de.oliver_heger.splaya.playback

import java.nio.file.{Files, Path}

import de.oliver_heger.splaya.FileTestHelper
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

object BufferFileManagerSpec {
  /** A prefix for temporary files. */
  private val FilePrefix = "BufferTestFile"

  /** A suffix for temporary files. */
  private val FileSuffix = ".tmp"
}

/**
 * Test class for ''BufferFileManager''.
 */
class BufferFileManagerSpec extends FlatSpec with Matchers with BeforeAndAfter with FileTestHelper {

  import de.oliver_heger.splaya.playback.BufferFileManagerSpec._

  after {
    tearDownTestFile()
  }

  /**
   * Creates a default test instance of ''BufferFileManager''.
   * @return the test manager instance
   */
  private def createManager(): BufferFileManager =
    new BufferFileManager(testDirectory, FilePrefix, FileSuffix)

  "A BufferFileManager" should "be empty initially" in {
    val manager = createManager()
    manager.read shouldBe 'empty
  }

  it should "not be full initially" in {
    val manager = createManager()
    manager should not be 'full
  }

  it should "create correct names for buffer files" in {
    val manager = createManager()
    val Count = 8
    val paths = for (i <- 0 until Count) yield manager.createPath()
    var index = 0
    paths foreach { p =>
      p.getParent should be(testDirectory)
      val name = p.getFileName.toString
      name should be(FilePrefix + index + FileSuffix)
      index += 1
    }
  }

  it should "allow appending and querying a path" in {
    val manager = createManager()
    val path = manager.createPath()
    manager append path
    manager.read.get should be(path)
  }

  it should "have capacity for two temporary files" in {
    val manager = createManager()
    val path1 = manager.createPath()
    val path2 = manager.createPath()
    manager append path1
    manager append path2
    manager.read.get should be(path1)
  }

  it should "throw an exception when appending to a full buffer" in {
    val manager = createManager()
    manager append manager.createPath()
    manager append manager.createPath()
    manager shouldBe 'full

    intercept[IllegalStateException] {
      manager append manager.createPath()
    }
  }

  it should "not throw when removing a non-existing file" in {
    val manager = createManager()
    val path = createPathInDirectory("nonExisting.file")
    Files.exists(path) shouldBe false

    manager.removePath(path) should be(path)
    Files.exists(path) shouldBe false
  }

  it should "be able to remove a file on disk" in {
    val manager = createManager()
    val path = createDataFile()
    Files.exists(path) shouldBe true

    manager.removePath(path) should be(path)
    Files.exists(path) shouldBe false
  }

  it should "allow checking out a path from the buffer" in {
    val manager = createManager()
    val path1 = manager.createPath()

    manager append path1
    manager.checkOut() should be(path1)
    manager.read shouldBe 'empty
  }

  it should "throw an exception when checking out from an empty buffer" in {
    val manager = createManager()

    intercept[NoSuchElementException] {
      manager.checkOut()
    }
  }

  it should "allow checking out multiple paths" in {
    val manager = createManager()
    val path1 = manager.createPath()
    val path2 = manager.createPath()
    val path3 = manager.createPath()

    manager append path1
    manager append path2
    manager.checkOut() should be(path1)
    manager append path3
    manager.checkOut() should be(path2)
    manager.read.get should be(path3)
    manager.checkOut() should be(path3)
  }

  it should "allow checking out and removing a path" in {
    val manager = createManager()
    val path = createDataFile()
    manager append path

    manager.checkOutAndRemove() should be(path)
    Files.exists(path) shouldBe false
  }

  it should "not throw when removing the paths in the buffer, but the buffer is empty" in {
    val manager = createManager()
    manager.removeContainedPaths() shouldBe empty
  }

  it should "be able to remove the paths currently contained in the buffer" in {
    val manager = createManager()
    val path1 = createDataFile()
    val path2 = createDataFile("some other data")
    manager append path1
    manager append path2

    val paths = manager.removeContainedPaths()
    paths should have length 2
    paths should contain(path1)
    paths should contain(path2)
    listManagedDirectory() shouldBe 'empty
  }

  /**
   * Creates a temporary file with the specified name.
   * @param name the file name
   * @return the newly created path
   */
  private def createFileWithName(name: String): Path =
    writeFileContent(createPathInDirectory(name), "Some data in " + name)

  /**
   * Creates a temporary file with a name that matches the data files managed
   * by this buffer.
   * @param index the index for generating unique names
   * @return the newly created path
   */
  private def createFileWithMatchingName(index: Int): Path =
    createFileWithName(FilePrefix + index + FileSuffix)

  it should "be able to clear all data files in the managed directory" in {
    createFileWithMatchingName(1)
    val path = createFileWithName("AnotherFile.dat")
    createFileWithMatchingName(42)
    val manager = createManager()

    manager.clearBufferDirectory()
    val paths = listManagedDirectory()
    paths.toSeq should have length 1
    paths should contain(path)
  }
}
