/*
 * Copyright 2015-2021 The Developers Team.
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

import akka.actor.ActorSystem
import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import akka.http.scaladsl.model.headers.{HttpCookie, `Set-Cookie`}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.testkit.TestKit
import akka.util.Timeout
import com.github.cloudfiles.core.delegate.ExtensibleFileSystem
import com.github.cloudfiles.core.http.HttpRequestSender.FailedResponseException
import com.github.cloudfiles.core.http.RetryAfterExtension.RetryAfterConfig
import com.github.cloudfiles.core.http.auth.BasicAuthConfig
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, HttpRequestSenderFactory}
import com.github.cloudfiles.core.http.{HttpRequestSender, Secret}
import com.github.cloudfiles.core.{FileSystem, Model}
import com.github.cloudfiles.crypt.alg.aes.Aes
import com.github.cloudfiles.crypt.fs.{CryptContentFileSystem, CryptNamesFileSystem}
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.archivehttp.io.{FileSystemMediaDownloader, HttpArchiveFileSystem, MediaDownloader}
import de.oliver_heger.linedj.archivehttpstart.spi.HttpArchiveProtocolSpec
import org.mockito.Matchers.{any, eq => argEq}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.security.Key
import scala.concurrent.duration._
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

  /** The name of the content file of the test archive. */
  private val ContentFile = "music-content.json"

  /** A test archive configuration. */
  private val ArchiveConfig = HttpArchiveConfig(processorTimeout = Timeout(2.minutes), processorCount = 2,
    propagationBufSize = 4, maxContentSize = 8192, downloadBufferSize = 4096, downloadMaxInactivity = 10.seconds,
    downloadReadChunkSize = 4096, timeoutReadSize = 17, requestQueueSize = 24, cryptUriCacheSize = 0,
    archiveURI = "", archiveName = "", downloadConfig = null, metaMappingConfig = null, contentMappingConfig = null,
    needsCookieManagement = false, protocol = null, authFunc = null)

  /**
    * A test archive startup configuration with basic properties. Some test
    * cases derive modified configurations from this object.
    */
  private val ArchiveStartupConfig = HttpArchiveStartupConfig(archiveConfig = ArchiveConfig,
    archiveURI = ArchiveUri, archiveName = ArchiveName, requestQueueSize = 16, needsCookieManagement = false,
    needsRetrySupport = false)

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

  /** Type alias for the protocol spec used by these tests. */
  type ProtocolSpecType = HttpArchiveProtocolSpec[String, FileType, FolderType]
}

/**
  * Test class for ''FileSystemMediaDownloaderFactory''.
  */
class FileSystemMediaDownloaderFactorySpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar {
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
        fs.keyEncrypt should be(key)
        fs.keyDecrypt should be(key)
        fs.algorithm should be(Aes)
        fs.delegate match {
          case fsn: CryptNamesFileSystem[_, _, _] =>
            fsn.keyEncrypt should be(key)
            fsn.keyDecrypt should be(key)
            fsn.algorithm should be(Aes)
            helper.checkFileSystem(fsn.delegate)
          case f => fail("Unexpected delegate file system: " + f)
        }
      case f => fail("Unexpected file system: " + f)
    }
  }

  it should "create a downloader with multi-host support" in {
    val helper = new FactoryTestHelper

    val downloader = helper.expectMultiHostSenderCreation(createSenderConfig())
      .prepareProtocolSpec { spec =>
        when(spec.requiresMultiHostSupport).thenReturn(true)
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
      when(spec.createFileSystemFromConfig(ArchiveUri, ArchiveConfig.processorTimeout))
        .thenReturn(Failure(exception))
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
    private val fileSystem = mock[ExtensibleFileSystem[String, FileType, FolderType, FolderContentType]]

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
    def prepareProtocolSpec(f: ProtocolSpecType => Unit): FactoryTestHelper = {
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
          downloader.archiveFileSystem.rootPath should be(RootPath)
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
    private def createProtocolSpec(): ProtocolSpecType = {
      val spec = mock[ProtocolSpecType]
      when(spec.requiresMultiHostSupport).thenReturn(false)
      val fsSpec = HttpArchiveFileSystem(fileSystem, RootPath, ContentFile)
      when(spec.createFileSystemFromConfig(ArchiveUri, ArchiveConfig.processorTimeout))
        .thenReturn(Success(fsSpec))
      spec
    }
  }

}
