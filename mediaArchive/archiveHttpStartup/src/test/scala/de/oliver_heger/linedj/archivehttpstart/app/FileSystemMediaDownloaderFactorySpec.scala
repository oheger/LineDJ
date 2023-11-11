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

package de.oliver_heger.linedj.archivehttpstart.app

import com.github.cloudfiles.core.FileSystem.Operation
import com.github.cloudfiles.core.delegate.ExtensibleFileSystem
import com.github.cloudfiles.core.http.HttpRequestSender.FailedResponseException
import com.github.cloudfiles.core.http.RetryAfterExtension.RetryAfterConfig
import com.github.cloudfiles.core.http.auth.BasicAuthConfig
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, HttpRequestSenderFactory}
import com.github.cloudfiles.core.http.{HttpRequestSender, Secret}
import com.github.cloudfiles.core.{FileSystem, Model}
import com.github.cloudfiles.crypt.alg.aes.Aes
import com.github.cloudfiles.crypt.fs.{CryptContentFileSystem, CryptNamesFileSystem}
import com.github.cloudfiles.crypt.service.CryptService
import de.oliver_heger.linedj.AsyncTestHelper
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.archivehttp.io.{FileSystemMediaDownloader, HttpArchiveFileSystem, MediaDownloader}
import de.oliver_heger.linedj.archivehttpstart.spi.HttpArchiveProtocolSpec
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.{ActorSystem, typed}
import org.apache.pekko.http.scaladsl.model.headers.{HttpCookie, `Set-Cookie`}
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes, Uri}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.Timeout
import org.mockito.ArgumentMatchers.{any, eq => argEq}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.security.{Key, SecureRandom}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.existentials
import scala.util.{Failure, Success, Try}

object FileSystemMediaDownloaderFactorySpec {
  /** The URI for the test archive. */
  private val ArchiveUri = "https://test.archive.example.org/test"

  /** A name for the test archive. */
  private val ArchiveName = "HyperMusic"

  /** The name to be used by the actors created by the test factory. */
  private val ActorName = "TestArchiveActor"

  /** The root path of the test archive. */
  private val RootPath = "/archive/root"

  /** A test archive configuration. */
  private val ArchiveConfig = HttpArchiveConfig(processorTimeout = Timeout(2.minutes), processorCount = 2,
    propagationBufSize = 4, maxContentSize = 8192, downloadBufferSize = 4096, downloadMaxInactivity = 10.seconds,
    downloadReadChunkSize = 4096, timeoutReadSize = 17, archiveBaseUri = ArchiveUri, archiveName = ArchiveName,
    downloadConfig = null, downloader = null, contentPath = Uri.Path("content.json"), mediaPath = Uri.Path("media"),
    metaDataPath = Uri.Path("meta"))

  /**
    * A test archive startup configuration with basic properties. Some test
    * cases derive modified configurations from this object.
    */
  private val ArchiveStartupConfig = HttpArchiveStartupConfig(archiveConfig = ArchiveConfig,
    requestQueueSize = 16, needsCookieManagement = false, needsRetrySupport = false,
    cryptCacheSize = 2048, cryptChunkSize = 32)

  /** The auth config used by the tests. */
  private val TestAuthConfig = BasicAuthConfig("scott", Secret("tiger"))

  /**
    * Convenience function to create a configuration for a request sender that
    * already sets the predefined values.
    *
    * @param retryAfterConfig the retry configuration
    * @return the ''HttpRequestSenderConfig''
    */
  private def createSenderConfig(retryAfterConfig: Option[RetryAfterConfig] = None): HttpRequestSenderConfig =
    HttpRequestSenderConfig(actorName = Some(ActorName), queueSize = ArchiveStartupConfig.requestQueueSize,
      authConfig = TestAuthConfig, retryAfterConfig = retryAfterConfig)

  /** The type of files in the test file system. */
  type FileType = Model.File[String]

  /** The type of folders in the test file system. */
  type FolderType = Model.Folder[String]

  /** The type of the folder content in the test file system. */
  type FolderContentType = Model.FolderContent[String, Model.File[String], Model.Folder[String]]

  /**
    * A stub implementation of [[HttpArchiveProtocolSpec]] that can be
    * configured to return a specific file system and other properties that can
    * be customize.
    *
    * @param fileSystem the file system to be returned
    */
  class HttpArchiveTestProtocolSpec(fileSystem: ExtensibleFileSystem[String, FileType, FolderType, FolderContentType])
    extends HttpArchiveProtocolSpec {
    override type ID = String
    override type File = FileType
    override type Folder = FolderType

    override def name: String = "testHttpArchiveProtocol"

    override def requiresMultiHostSupport: Boolean = multiHostSupportFlag

    /** A flag that controls whether multi-host support is required. */
    var multiHostSupportFlag: Boolean = false

    /** An exception to be raised when creating the file system. */
    var creationException: Option[Throwable] = None

    override def createFileSystemFromConfig(sourceUri: String, timeout: Timeout):
    Try[HttpArchiveFileSystem[ID, File, Folder]] = {
      creationException.fold[Try[HttpArchiveFileSystem[ID, File, Folder]]](Try {
        if (sourceUri != ArchiveUri) throw new AssertionError("Unexpected sourceUri: " + sourceUri)
        if (timeout != ArchiveConfig.processorTimeout)
          throw new AssertionError("Unexpected timeout: " + timeout)
        HttpArchiveFileSystem[String, FileType, FolderType](fileSystem, Uri.Path(RootPath))
      }) { exception => Failure(exception) }
    }
  }
}

/**
  * Test class for ''FileSystemMediaDownloaderFactory''.
  */
class FileSystemMediaDownloaderFactorySpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with AsyncTestHelper {
  def this() = this(ActorSystem("FileSystemMediaDownloaderFactorySpec"))

  /** The test kit for typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    testKit.shutdownTestKit()
    super.afterAll()
  }

  import FileSystemMediaDownloaderFactorySpec._

  "FileSystemMediaDownloaderFactory" should "create a downloader with basic properties" in {
    val helper = new FactoryTestHelper

    val downloader = helper.expectSenderCreation(createSenderConfig())
      .createDownloaderSuccess()
    downloader.httpSender should be(helper.probeRequestActor.ref)
    helper.checkFileSystem(downloader.archiveFileSystem.fileSystem)
  }

  it should "create a downloader using an encrypted file system" in {
    val Password = "#SecretKey*"
    val key = Aes.keyFromString(Password)
    val helper = new FactoryTestHelper

    val downloader = helper.expectSenderCreation(createSenderConfig())
      .createDownloaderSuccess(optKey = Some(key))
    downloader.archiveFileSystem.fileSystem match {
      case fs: CryptContentFileSystem[_, _, _] =>
        fs.config.keyEncrypt should be(key)
        fs.config.keyDecrypt should be(key)
        fs.config.algorithm should be(Aes)
        fs.delegate match {
          case fsn: CryptNamesFileSystem[_, _, _] =>
            fsn.namesConfig.ignoreUnencrypted shouldBe true
            fsn.namesConfig.cryptConfig.keyEncrypt should be(key)
            fsn.namesConfig.cryptConfig.keyDecrypt should be(key)
            fsn.namesConfig.cryptConfig.algorithm should be(Aes)
            helper.checkFileSystem(fsn.delegate)
          case f => fail("Unexpected delegate file system: " + f)
        }
      case f => fail("Unexpected file system: " + f)
    }
  }

  it should "configure a cached resolver for the crypt names file system" in {
    val Password = "<ThePassword>"
    val key = Aes.keyFromString(Password)
    implicit val random: SecureRandom = new SecureRandom
    val testKit = ActorTestKit()
    implicit val system: typed.ActorSystem[Nothing] = testKit.system
    val httpActor = testKit.spawn(HttpRequestSender(Uri("http://test.example.org")))

    def fileMock(id: String, name: String): FileType = {
      val file = mock[FileType]
      when(file.id).thenReturn(id)
      when(file.name).thenReturn(CryptService.encryptTextToBase64(Aes, key, name))
      file
    }

    def stubOperation[A](result: A): Operation[A] = Operation { sender =>
      sender should be(httpActor)
      Future.successful(result)
    }

    val FileID1 = "fid1"
    val FileID2 = "fidOther"
    val FileName1 = "testFile1.txt"
    val FileName2 = "otherFile.dat"
    val RootID = "RootFolderID"
    val content = Model.FolderContent(RootID,
      Map(FileID1 -> fileMock(FileID1, FileName1),
        FileID2 -> fileMock(FileID2, FileName2)),
      Map.empty)
    val helper = new FactoryTestHelper
    val downloader = helper.expectSenderCreation(createSenderConfig())
      .createDownloaderSuccess(optKey = Some(key))
    val fileSystem = downloader.archiveFileSystem.fileSystem
    when(helper.fileSystem.rootID).thenReturn(stubOperation(RootID))
    doReturn(stubOperation(content)).when(helper.fileSystem).folderContent(RootID)

    futureResult(fileSystem.resolvePath(FileName1).run(httpActor)) should be(FileID1)
    futureResult(fileSystem.resolvePath(FileName2).run(httpActor)) should be(FileID2)
    verify(helper.fileSystem).folderContent(RootID) // only a single invocation
    testKit.shutdownTestKit()
  }

  it should "create a downloader with multi-host support" in {
    val helper = new FactoryTestHelper

    val downloader = helper.expectMultiHostSenderCreation(createSenderConfig())
      .prepareProtocolSpec { spec =>
        spec.multiHostSupportFlag = true
      }.createDownloaderSuccess()
    downloader.httpSender should be(helper.probeRequestActor.ref)
    helper.checkFileSystem(downloader.archiveFileSystem.fileSystem)
  }

  it should "create a downloader with retry support" in {
    val startupConfig = ArchiveStartupConfig.copy(needsRetrySupport = true)
    val expSenderConfig = createSenderConfig(retryAfterConfig = Some(RetryAfterConfig()))
    val helper = new FactoryTestHelper

    val downloader = helper.expectSenderCreation(expSenderConfig)
      .createDownloaderSuccess(startupConfig = startupConfig)
    downloader.httpSender should be(helper.probeRequestActor.ref)
  }

  it should "create a downloader with cookie management support" in {
    val startupConfig = ArchiveStartupConfig.copy(needsCookieManagement = true)
    val helper = new FactoryTestHelper

    val downloader = helper.expectSenderCreation(createSenderConfig())
      .createDownloaderSuccess(startupConfig = startupConfig)
    val probeClient = testKit.createTestProbe[HttpRequestSender.Result]()
    downloader.httpSender ! HttpRequestSender.SendRequest(HttpRequest(uri = "https://example.org"),
      "someData", probeClient.ref)

    val fwdRequest = helper.probeRequestActor.expectMessageType[HttpRequestSender.SendRequest]
    val failedResponse = HttpResponse(status = StatusCodes.Unauthorized,
      headers = List(`Set-Cookie`(HttpCookie(name = "someAuthCookie", value = "1234567890"))))
    fwdRequest.replyTo ! HttpRequestSender.FailedResult(fwdRequest, FailedResponseException(failedResponse))
    helper.probeRequestActor.expectMessageType[HttpRequestSender.SendRequest]
    downloader.httpSender.path.toString should endWith(ActorName + "_cookie")
  }

  it should "handle a failure when creating the file system from the protocol spec" in {
    val exception = new IllegalArgumentException("Could not create file system!")
    val helper = new FactoryTestHelper

    helper.prepareProtocolSpec { spec =>
      spec.creationException = Some(exception)
    }.createDownloader() match {
      case Failure(ex) => ex should be(exception)
      case Success(value) => fail("Could create downloader: " + value)
    }
  }

  /**
    * A test helper class managing a factory to be tested and its dependencies
    * and helper objects.
    */
  private class FactoryTestHelper {
    /** The mock for the factory for the request sender actor. */
    private val requestSenderFactory = mock[HttpRequestSenderFactory]

    /** Mock for the file system. */
    val fileSystem: ExtensibleFileSystem[String, FileType, FolderType, FolderContentType] =
      mock[ExtensibleFileSystem[String, FileType, FolderType, FolderContentType]]

    /** The mock for the protocol specification. */
    private val protocolSpec = createProtocolSpec()

    /** A probe acting as request actor returned from the sender factory. */
    val probeRequestActor: TestProbe[HttpRequestSender.HttpCommand] =
      testKit.createTestProbe[HttpRequestSender.HttpCommand]()

    /** The downloader factory to be tested. */
    private val downloaderFactory = new FileSystemMediaDownloaderFactory(requestSenderFactory)

    /**
      * Prepares the mock for the request sender factory to expect the creation
      * of a sender actor with the configuration provided.
      *
      * @param config the config for the request sender actor
      * @return this test helper
      */
    def expectSenderCreation(config: HttpRequestSenderConfig): FactoryTestHelper = {
      when(requestSenderFactory.createRequestSender(any(), argEq(ArchiveUri), argEq(config)))
        .thenReturn(probeRequestActor.ref)
      this
    }

    /**
      * Prepares the mock for the request sender factory to expect the creation
      * of a multi-host sender with the configuration provided.
      *
      * @param config the config for the multi-host request sender actor
      * @return this test helper
      */
    def expectMultiHostSenderCreation(config: HttpRequestSenderConfig): FactoryTestHelper = {
      when(requestSenderFactory.createMultiHostRequestSender(any(), argEq(config), any()))
        .thenReturn(probeRequestActor.ref)
      this
    }

    /**
      * Invokes the given initialization function on the mock protocol spec,
      * so that it can prepare some properties.
      *
      * @param f the initialization function
      * @return this test helper
      */
    def prepareProtocolSpec(f: HttpArchiveTestProtocolSpec => Unit): FactoryTestHelper = {
      f(protocolSpec)
      this
    }

    /**
      * Invokes the test downloader factory with the given parameters.
      *
      * @param startupConfig the startup config
      * @param optKey        an optional key for encryption
      * @return the result from the factory
      */
    def createDownloader(startupConfig: HttpArchiveStartupConfig = ArchiveStartupConfig, optKey: Option[Key] = None):
    Try[MediaDownloader] =
      downloaderFactory.createDownloader(protocolSpec, startupConfig, TestAuthConfig, ActorName, optKey)

    /**
      * Invokes the test downloader factory with the given parameters and
      * expects a success result.
      *
      * @param startupConfig the startup config
      * @param optKey        an optional key for encryption
      * @return the downloader created by the factory
      */
    def createDownloaderSuccess(startupConfig: HttpArchiveStartupConfig = ArchiveStartupConfig,
                                optKey: Option[Key] = None): FileSystemMediaDownloader[_] =
      createDownloader(startupConfig, optKey) match {
        case Success(downloader: FileSystemMediaDownloader[_]) =>
          downloader.archiveFileSystem.rootPath should be(Uri.Path(RootPath))
          downloader
        case r => fail("Unexpected result: " + r)
      }

    /**
      * Checks whether the given file system is the one returned by the
      * protocol spec.
      *
      * @param fs the ''FileSystem'' to check
      */
    def checkFileSystem(fs: FileSystem[_, _, _, _]): Unit = {
      fs should be(fileSystem)
    }

    /**
      * Creates the mock for the ''HttpArchiveProtocolSpec'' and configures
      * some default behavior.
      *
      * @return the mock protocol spec
      */
    private def createProtocolSpec(): HttpArchiveTestProtocolSpec =
      new HttpArchiveTestProtocolSpec(fileSystem)
  }
}
