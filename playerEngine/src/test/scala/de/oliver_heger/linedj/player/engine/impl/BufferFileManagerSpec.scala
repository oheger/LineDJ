package de.oliver_heger.linedj.player.engine.impl

import java.nio.file.{Files, Path, Paths}

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.player.engine.PlayerConfig
import de.oliver_heger.linedj.player.engine.impl.BufferFileManager.BufferFile
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

object BufferFileManagerSpec {
  /** A prefix for temporary files. */
  private val FilePrefix = "BufferTestFile"

  /** A suffix for temporary files. */
  private val FileSuffix = ".tmp"

  private val SourceLengths = List(1024L, 512L, 768L)
}

/**
 * Test class for ''BufferFileManager''.
 */
class BufferFileManagerSpec extends FlatSpec with Matchers with BeforeAndAfter with FileTestHelper {
  import BufferFileManagerSpec._

  after {
    tearDownTestFile()
  }

  /**
   * Creates a default test instance of ''BufferFileManager''.
    *
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
    val file = BufferFile(path, SourceLengths)
    manager append file
    manager.read.get should be(file)
  }

  it should "have capacity for two temporary files" in {
    val manager = createManager()
    val path1 = manager.createPath()
    val path2 = manager.createPath()
    val file = BufferFile(path1, SourceLengths)
    manager append file
    manager append BufferFile(path2, Nil)
    manager.read.get should be(file)
  }

  it should "throw an exception when appending to a full buffer" in {
    val manager = createManager()
    manager append BufferFile(manager.createPath(), SourceLengths)
    manager append BufferFile(manager.createPath(), Nil)
    manager shouldBe 'full

    intercept[IllegalStateException] {
      manager append BufferFile(manager.createPath(), SourceLengths)
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

  it should "allow checking out a file from the buffer" in {
    val manager = createManager()
    val path1 = manager.createPath()
    val file = BufferFile(path1, SourceLengths)

    manager append file
    manager.checkOut() should be(file)
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
    val file1 = BufferFile(manager.createPath(), Nil)
    val file2 = BufferFile(manager.createPath(), SourceLengths)
    val file3 = BufferFile(manager.createPath(), Nil)

    manager append file1
    manager append file2
    manager.checkOut() should be(file1)
    manager append file3
    manager.checkOut() should be(file2)
    manager.read.get should be(file3)
    manager.checkOut() should be(file3)
  }

  it should "allow checking out and removing a path" in {
    val manager = createManager()
    val path = createDataFile()
    val file = BufferFile(path, SourceLengths)
    manager append file

    manager.checkOutAndRemove() should be(file)
    Files.exists(path) shouldBe false
  }

  it should "not throw when removing the paths in the buffer, but the buffer is empty" in {
    val manager = createManager()
    manager.removeContainedPaths() shouldBe empty
  }

  it should "be able to remove the paths currently contained in the buffer" in {
    val manager = createManager()
    val file1 = BufferFile(createDataFile(), SourceLengths)
    val file2 = BufferFile(createDataFile("some other data"), Nil)
    manager append file1
    manager append file2

    val paths = manager.removeContainedPaths()
    paths should have length 2
    paths should contain(file1)
    paths should contain(file2)
    listManagedDirectory() shouldBe 'empty
  }

  /**
   * Creates a temporary file with the specified name.
    *
    * @param name the file name
   * @return the newly created path
   */
  private def createFileWithName(name: String): Path =
    writeFileContent(createPathInDirectory(name), "Some data in " + name)

  /**
   * Creates a temporary file with a name that matches the data files managed
   * by this buffer.
    *
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

  /**
    * Creates a test player configuration.
    *
    * @return the test configuration
    */
  private def createConfig(): PlayerConfig =
    PlayerConfig(bufferFilePrefix = FilePrefix, bufferFileExtension = FileSuffix,
      mediaManagerActor = null, actorCreator = (props, name) => null,
      bufferTempPathParts = List("lineDJTest", "temp"),
      bufferTempPath = Some(createPathInDirectory("bufferTemp")))

  it should "initialize itself from a player configuration" in {
    val config = createConfig()
    val manager = BufferFileManager(config)

    manager.prefix should be(FilePrefix)
    manager.extension should be(FileSuffix)
    manager.directory should be(config.bufferTempPath.get)
  }

  it should "create the buffer temp directory if necessary" in {
    val config = createConfig()
    val manager = BufferFileManager(config)

    Files.exists(manager.directory) shouldBe true
  }

  it should "create a buffer directory below the user's directory" in {
    val expectedPath = Paths.get(System.getProperty("user.home"), "lineDJTest", "temp")
    val config = createConfig().copy(bufferTempPath = None)
    val manager = BufferFileManager(config)

    manager.directory should be(expectedPath)
    Files delete expectedPath
  }
}
