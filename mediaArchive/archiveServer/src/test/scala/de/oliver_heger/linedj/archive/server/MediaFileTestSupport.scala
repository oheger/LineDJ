package de.oliver_heger.linedj.archive.server

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archivecommon.download.DownloadConfig
import org.apache.pekko.util.Timeout
import org.scalatest.{BeforeAndAfterEach, Suite}

import java.nio.file.{Files, Path, Paths}
import scala.collection.immutable.Seq
import scala.concurrent.duration.DurationInt

object MediaFileTestSupport:
  /**
    * An archive configuration with some dummy settings. Concrete
    * configurations used by tests are created based on this object.
    */
  private val BaseMediaArchiveConfig = MediaArchiveConfig(
    metadataReadChunkSize = 8000,
    infoSizeLimit = 16000,
    tagSizeLimit = 2000,
    processingTimeout = Timeout(5.minutes),
    metadataMediaBufferSize = 32000,
    metadataPersistencePath = Paths.get("some", "metadata", "path"),
    metadataPersistenceChunkSize = 64,
    metadataPersistenceParallelCount = 1,
    excludedFileExtensions = Set.empty,
    includedFileExtensions = Set.empty,
    processorCount = 4,
    contentFile = None,
    infoParserTimeout = Timeout(2.minutes),
    scanMediaBufferSize = 50,
    blockingDispatcherName = "blocking-dispatcher",
    downloadConfig = DownloadConfig(
      downloadTimeout = 10.minutes,
      downloadCheckInterval = 15.minutes,
      downloadChunkSize = 128
    ),
    rootPath = null,
    archiveName = "TBD"
  )

  /**
    * Returns a [[MediaArchiveConfig]] with dummy settings and the given
    * parameters.
    *
    * @param root the root path of the archive
    * @param name the name of the archive
    * @return the configuration for this media archive
    */
  def createArchiveConfig(root: Path, name: String): MediaArchiveConfig =
    BaseMediaArchiveConfig.copy(rootPath = root, archiveName = name)

  /**
    * Creates a dummy server configuration that contains the given archive
    * configurations.
    *
    * @param archiveConfigs the archive configurations to include
    * @return the server configuration
    */
  def createServerConfig(archiveConfigs: Seq[MediaArchiveConfig]): ArchiveServerConfig =
    ArchiveServerConfig(
      serverPort = 8889,
      timeout = 3.minutes,
      archiveConfigs = archiveConfigs
    )
end MediaFileTestSupport

/**
  * A test helper trait providing functionality to create test media files and
  * configurations for archives that host media files. The trait can simply be
  * mixed into test classes.
  */
trait MediaFileTestSupport extends BeforeAndAfterEach with FileTestHelper:
  this: Suite =>

  override def afterEach(): Unit =
    tearDownTestFile()
    super.afterEach()

  import MediaFileTestSupport.*

  /**
    * Creates a file with the given content at the specified path. All parent
    * directories are created if necessary.
    *
    * @param path    the target path
    * @param content the content of the file
    * @return the path to the created file
    */
  def writeFileAt(path: Path, content: String): Path =
    Files.createDirectories(path.getParent)
    writeFileContent(path, content)

  /**
    * Creates a root directory for a media archive and a corresponding archive
    * configuration based on the passed in name.
    *
    * @param archiveName the name of the archive
    * @return the configuration for this archive
    */
  def createArchiveConfigWithRootPath(archiveName: String): MediaArchiveConfig =
    val archivePath = Files.createDirectory(createPathInDirectory(archiveName))
    createArchiveConfig(archivePath, archiveName)

  /**
    * Creates a file with the given content and relative path under the root
    * folder of a media archive.
    *
    * @param archiveConfig the configuration of the media archive
    * @param path          the relative path of the media file
    * @param content       the content of the file
    * @return the path to the resulting media file
    */
  def writeMediaFile(archiveConfig: MediaArchiveConfig, path: Path, content: String): Path =
    writeFileAt(archiveConfig.rootPath.resolve(path), content)  
    