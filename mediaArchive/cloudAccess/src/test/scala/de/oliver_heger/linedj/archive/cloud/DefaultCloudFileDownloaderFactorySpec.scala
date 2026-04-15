/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.archive.cloud

import com.github.cloudfiles.core.FileSystem.Operation
import com.github.cloudfiles.core.delegate.ExtensibleFileSystem
import com.github.cloudfiles.core.http.RetryAfterExtension.RetryAfterConfig
import com.github.cloudfiles.core.http.auth.BasicAuthConfig
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, HttpRequestSenderFactory}
import com.github.cloudfiles.core.http.{HttpRequestSender, Secret}
import com.github.cloudfiles.core.{FileSystem, Model}
import com.github.cloudfiles.crypt.alg.aes.Aes
import com.github.cloudfiles.crypt.fs.{CryptContentFileSystem, CryptNamesFileSystem}
import com.github.cloudfiles.crypt.service.CryptService
import de.oliver_heger.linedj.archive.cloud.auth.{AuthConfigFactory, BasicAuthMethod, Credentials}
import de.oliver_heger.linedj.archive.cloud.spi.CloudArchiveFileSystemFactory
import de.oliver_heger.linedj.archive.cloud.spi.CloudArchiveFileSystemFactory.CloudArchiveFileSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.{ActorSystem, typed}
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.Timeout
import org.mockito.ArgumentMatchers.{any, eq as argEq}
import org.mockito.Mockito.*
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, BeforeAndAfterAll}
import org.scalatestplus.mockito.MockitoSugar

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Try}

object DefaultCloudFileDownloaderFactorySpec:
  /** The URI for the test archive. */
  private val ArchiveUri = "https://test.archive.example.org/test"

  /** A name for the test archive. */
  private val ArchiveName = "HyperMusic"

  /** The name to be used by the actors created by the test factory. */
  private val ActorName = "TestArchiveActor"

  /** The root path of the test archive. */
  private val RootPath = "/archive/root"

  /** The timeout set for the test archive. */
  private val ArchiveTimeout = Timeout(47.seconds)

  /** Test size of the archive request queue. */
  private val ArchiveQueueSize = 18

  /** The test authentication method. */
  private val TestAuthMethod = BasicAuthMethod("testAuthRealm")

  /** The auth config used by the tests. */
  private val TestAuthConfig = BasicAuthConfig("scott", Secret("tiger"))

  /** A configuration used by tests for encrypted file systems. */
  private val TestCryptConfig = ArchiveCryptConfig(
    cryptCacheSize = 2048,
    cryptChunkSize = 32
  )

  /** The type of files in the test file system. */
  type FileType = Model.File[String]

  /** The type of folders in the test file system. */
  type FolderType = Model.Folder[String]

  /** The type of the folder content in the test file system. */
  type FolderContentType = Model.FolderContent[String, Model.File[String], Model.Folder[String]]

  /**
    * Convenience function to create a configuration for a request sender that
    * already sets the predefined values.
    *
    * @param actorName the expected actor base name
    * @return the [[HttpRequestSenderConfig]]
    */
  private def createSenderConfig(actorName: String = ActorName): HttpRequestSenderConfig =
    HttpRequestSenderConfig(
      actorName = Some(actorName),
      queueSize = ArchiveQueueSize,
      authConfig = TestAuthConfig,
      retryAfterConfig = Some(RetryAfterConfig())
    )

  /**
    * A stub implementation of [[CloudArchiveFileSystemFactory]] that can be
    * configured to return a specific file system.
    *
    * @param fileSystem the file system to be returned
    */
  class CloudArchiveTestFileSystemFactory(fileSystem: CloudArchiveFileSystem[String, FileType, FolderType])
    extends CloudArchiveFileSystemFactory:
    override type ID = String
    override type File = FileType
    override type Folder = FolderType

    override def name: String = "testHttpArchiveProtocol"

    override def requiresMultiHostSupport: Boolean = multiHostSupportFlag

    /** A flag that controls whether multi-host support is required. */
    var multiHostSupportFlag: Boolean = false

    /** An exception to be raised when creating the file system. */
    var creationException: Option[Throwable] = None

    override def createFileSystem(sourceUri: String, timeout: Timeout): Try[CloudArchiveFileSystem[ID, File, Folder]] =
      creationException.fold[Try[CloudArchiveFileSystem[ID, File, Folder]]](Try:
        if sourceUri != ArchiveUri then throw new AssertionError("Unexpected sourceUri: " + sourceUri)
        if timeout != ArchiveTimeout then throw new AssertionError("Unexpected timeout: " + timeout)
        fileSystem
      ): exception =>
        Failure(exception)
  end CloudArchiveTestFileSystemFactory
end DefaultCloudFileDownloaderFactorySpec

/**
  * Test class for [[DefaultCloudFileDownloaderFactory]].
  */
class DefaultCloudFileDownloaderFactorySpec(testSystem: ActorSystem) extends TestKit(testSystem), AsyncFlatSpecLike,
  BeforeAndAfterAll, Matchers, MockitoSugar:
  def this() = this(ActorSystem("DefaultCloudFileDownloaderFactorySpec"))

  /** The test kit for typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit =
    testKit.shutdownTestKit()
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import DefaultCloudFileDownloaderFactorySpec.*

  "DefaultCloudFileDownloaderFactory" should "create a downloader with basic properties" in :
    val helper = new FactoryTestHelper

    helper.expectSenderCreation(createSenderConfig())
      .createDownloader(helper.createArchiveConfig()) map : downloader =>
      downloader.httpSender should be(helper.probeRequestActor.ref)
      helper.checkFileSystem(downloader.archiveFileSystem)

  it should "create a downloader using an encrypted file system" in :
    val Password = "#SecretKey*"
    val key = Aes.keyFromString(Password)
    val helper = new FactoryTestHelper

    helper.expectSenderCreation(createSenderConfig())
      .withCredential(ArchiveName + DefaultCloudFileDownloaderFactory.CryptKeySuffix, Password)
      .createDownloader(helper.createArchiveConfig(Some(TestCryptConfig))) map : downloader =>
      downloader.archiveFileSystem match
        case fs: CryptContentFileSystem[?, ?, ?] =>
          fs.config.keyEncrypt should be(key)
          fs.config.keyDecrypt should be(key)
          fs.config.algorithm should be(Aes)
          fs.delegate match
            case fsn: CryptNamesFileSystem[?, ?, ?] =>
              fsn.namesConfig.ignoreUnencrypted shouldBe true
              fsn.namesConfig.cryptConfig.keyEncrypt should be(key)
              fsn.namesConfig.cryptConfig.keyDecrypt should be(key)
              fsn.namesConfig.cryptConfig.algorithm should be(Aes)
              helper.checkFileSystem(fsn.delegate)
            case f => fail("Unexpected delegate file system: " + f)
        case f => fail("Unexpected file system: " + f)

  it should "immediately request the secret for the encrypted file system" in :
    val helper = new FactoryTestHelper

    helper.expectSenderCreation(createSenderConfig())
      .createDownloader(helper.createArchiveConfig(Some(TestCryptConfig)), completeAuthFuture = false)

    helper.requestedCredentials should contain(ArchiveName + DefaultCloudFileDownloaderFactory.CryptKeySuffix)

  it should "configure a cached resolver for the crypt names file system" in :
    val Password = "<ThePassword>"
    val key = Aes.keyFromString(Password)

    given SecureRandom = new SecureRandom

    given typed.ActorSystem[Nothing] = testKit.system

    val httpActor = testKit.spawn(HttpRequestSender(Uri("http://test.example.org")))

    def fileMock(id: String, name: String): FileType =
      val file = mock[FileType]
      when(file.id).thenReturn(id)
      when(file.name).thenReturn(CryptService.encryptTextToBase64(Aes, key, name))
      file

    def stubOperation[A](result: A): Operation[A] = Operation: sender =>
      sender should be(httpActor)
      Future.successful(result)

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

    helper.withCredential(ArchiveName + DefaultCloudFileDownloaderFactory.CryptKeySuffix, Password)
      .expectSenderCreation(createSenderConfig())
      .createDownloader(helper.createArchiveConfig(Some(TestCryptConfig))) flatMap : downloader =>
      val fileSystem = downloader.archiveFileSystem
      when(helper.fileSystem.rootID).thenReturn(stubOperation(RootID))
      doReturn(stubOperation(content)).when(helper.fileSystem).folderContent(RootID)

      for
        id1 <- fileSystem.resolvePath(FileName1).run(httpActor)
        id2 <- fileSystem.resolvePath(FileName2).run(httpActor)
      yield
        verify(helper.fileSystem).folderContent(RootID) // only a single invocation
        id1 should be(FileID1)
        id2 should be(FileID2)

  it should "create a downloader with multi-host support" in :
    val helper = new FactoryTestHelper

    helper.expectMultiHostSenderCreation(createSenderConfig())
      .prepareFileSystemFactory: factory =>
        factory.multiHostSupportFlag = true
      .createDownloader(helper.createArchiveConfig()) map : downloader =>
      downloader.httpSender should be(helper.probeRequestActor.ref)
      helper.checkFileSystem(downloader.archiveFileSystem)

  it should "handle a failure when creating the file system from the factory" in :
    val exception = new IllegalArgumentException("Could not create file system!")
    val helper = new FactoryTestHelper

    recoverToExceptionIf[IllegalArgumentException]:
      helper.prepareFileSystemFactory: spec =>
        spec.creationException = Some(exception)
      .createDownloader(helper.createArchiveConfig())
    .map: actualEx =>
      actualEx should be(exception)

  it should "use the archive name as base actor name if no base name is provided" in :
    val helper = new FactoryTestHelper

    helper.expectSenderCreation(createSenderConfig(ArchiveName))
      .createDownloader(helper.createArchiveConfig(optActorName = None)) map : downloader =>
      downloader.httpSender should be(helper.probeRequestActor.ref)
      helper.checkFileSystem(downloader.archiveFileSystem)

  it should "URL-encode the archive name when used as actor base name" in :
    val ArchiveNameWithSpecialCharacters = "My (cool) music #archive!"
    val EncodedArchiveName = "My%20%28cool%29%20music%20%23archive%21"
    val helper = new FactoryTestHelper

    helper.expectSenderCreation(createSenderConfig(EncodedArchiveName))
      .createDownloader(
        helper.createArchiveConfig(optActorName = None).copy(archiveName = ArchiveNameWithSpecialCharacters)
      ) map : downloader =>
      downloader.httpSender should be(helper.probeRequestActor.ref)

  "credentialKeys" should "return the keys for a non-encrypted archive" in :
    val helper = new FactoryTestHelper
    val config = helper.createArchiveConfig()

    val keys = DefaultCloudFileDownloaderFactory.credentialKeys(config)

    keys should contain theSameElementsAs TestAuthMethod.credentialKeys

  it should "return the keys for an encrypted archive" in :
    val helper = new FactoryTestHelper
    val config = helper.createArchiveConfig(optCryptConfig = Some(TestCryptConfig))

    val keys = DefaultCloudFileDownloaderFactory.credentialKeys(config)

    val expectedKeys = TestAuthMethod.credentialKeys + (ArchiveName + DefaultCloudFileDownloaderFactory.CryptKeySuffix)
    keys should contain theSameElementsAs expectedKeys

  /**
    * A test helper class managing a factory to be tested and its dependencies
    * and helper objects.
    */
  private class FactoryTestHelper:
    /** The mock for the factory for the request sender actor. */
    private val requestSenderFactory = mock[HttpRequestSenderFactory]

    /** The promise for the future returned by the auth factory. */
    private val promiseAuthConfig = Promise[BasicAuthConfig]()

    /** The mock for the authentication factory. */
    private val authConfigFactory = createAuthConfigFactory()

    /** A map to store the requested credentials. */
    private val requestedCredentialsMap = new ConcurrentHashMap[String, Boolean]

    /** Mock for the file system. */
    val fileSystem: ExtensibleFileSystem[String, FileType, FolderType, FolderContentType] =
      mock[ExtensibleFileSystem[String, FileType, FolderType, FolderContentType]]

    /** The test file system factory. */
    private val fileSystemFactory = createFileSystemFactory()

    /**
      * A map for storing credentials that are to be returned by the
      * credentials resolver function.
      */
    private val credentials = new ConcurrentHashMap[String, Secret]

    /** A probe acting as request actor returned from the sender factory. */
    val probeRequestActor: TestProbe[HttpRequestSender.HttpCommand] =
      testKit.createTestProbe[HttpRequestSender.HttpCommand]()

    /** The downloader factory to be tested. */
    private val downloaderFactory =
      new DefaultCloudFileDownloaderFactory(authConfigFactory, requestSenderFactory)

    /**
      * Creates a [[CloudArchiveConfig]] with default settings and the provided
      * options.
      *
      * @param optCryptConfig the optional config for encryption
      * @param optActorName   the optional base name for actors
      * @return the config for the cloud archive
      */
    def createArchiveConfig(optCryptConfig: Option[ArchiveCryptConfig] = None,
                            optActorName: Option[String] = Some(ActorName)): CloudArchiveConfig =
      CloudArchiveConfig(
        archiveBaseUri = ArchiveUri,
        archiveName = ArchiveName,
        authMethod = TestAuthMethod,
        timeout = ArchiveTimeout,
        requestQueueSize = ArchiveQueueSize,
        fileSystemFactory = fileSystemFactory,
        optCryptConfig = optCryptConfig,
        optActorBaseName = optActorName
      )

    /**
      * Adds a credential to this object which can be queried by the test
      * resolver function used by the test object.
      *
      * @param key   the key of the credential
      * @param value the value of the credential
      * @return this test helper
      */
    def withCredential(key: String, value: String): FactoryTestHelper =
      credentials.put(key, Secret(value))
      this

    /**
      * Prepares the mock for the request sender factory to expect the creation
      * of a sender actor with the configuration provided.
      *
      * @param config the config for the request sender actor
      * @return this test helper
      */
    def expectSenderCreation(config: HttpRequestSenderConfig): FactoryTestHelper =
      when(requestSenderFactory.createRequestSender(any(), argEq(ArchiveUri), argEq(config)))
        .thenReturn(probeRequestActor.ref)
      this

    /**
      * Prepares the mock for the request sender factory to expect the creation
      * of a multi-host sender with the configuration provided.
      *
      * @param config the config for the multi-host request sender actor
      * @return this test helper
      */
    def expectMultiHostSenderCreation(config: HttpRequestSenderConfig): FactoryTestHelper =
      when(requestSenderFactory.createMultiHostRequestSender(any(), argEq(config), any()))
        .thenReturn(probeRequestActor.ref)
      this

    /**
      * Invokes the given initialization function on the test file system
      * factory, so that it can prepare some properties.
      *
      * @param f the initialization function
      * @return this test helper
      */
    def prepareFileSystemFactory(f: CloudArchiveTestFileSystemFactory => Unit): FactoryTestHelper =
      f(fileSystemFactory)
      this

    /**
      * Invokes the test downloader factory with the given configuration. It
      * can be configured whether the future from the auth factory should
      * complete immediately. For some test scenarios, this is not desired.
      *
      * @param archiveConfig      the configuration of the archive
      * @param completeAuthFuture flag whether the auth config future should
      *                           complete
      * @return the result from the factory
      */
    def createDownloader(archiveConfig: CloudArchiveConfig,
                         completeAuthFuture: Boolean = true): Future[FileSystemCloudFileDownloader[?, ?, ?]] =
      if completeAuthFuture then
        promiseAuthConfig.success(TestAuthConfig)
      downloaderFactory.createDownloader(archiveConfig) map : downloader =>
        downloader.asInstanceOf[FileSystemCloudFileDownloader[?, ?, ?]]

    /**
      * Checks whether the given file system is the one returned by the
      * protocol spec.
      *
      * @param fs the ''FileSystem'' to check
      * @return the outcome of the check
      */
    def checkFileSystem(fs: FileSystem[_, _, _, _]): Assertion =
      fs should be(fileSystem)

    /**
      * Verifies that the [[AuthConfigFactory]] was invoked with the correct
      * configuration.
      *
      * @param archiveConfig the expected configuration
      * @return this test helper
      */
    def checkAuthFactoryInvocation(archiveConfig: CloudArchiveConfig): FactoryTestHelper =
      verify(authConfigFactory).createAuthConfig(archiveConfig)
      this

    /**
      * Returns a [[Set]] with the credential keys that have been passed to the
      * resolver function.
      *
      * @return the requested credentials
      */
    def requestedCredentials: Set[String] =
      import scala.jdk.CollectionConverters.*
      requestedCredentialsMap.keySet().asScala.toSet

    /**
      * Creates the test implementation for the
      * [[CloudArchiveFileSystemFactory]].
      *
      * @return the test file system factory
      */
    private def createFileSystemFactory(): CloudArchiveTestFileSystemFactory =
      new CloudArchiveTestFileSystemFactory(fileSystem)

    /**
      * Creates a mock for the [[AuthConfigFactory]] that returns the test
      * authentication configuration.
      *
      * @return the mock [[AuthConfigFactory]]
      */
    private def createAuthConfigFactory(): AuthConfigFactory =
      val authFactory = mock[AuthConfigFactory]
      val resolverFunc = createResolverFunc()
      when(authFactory.createAuthConfig(any())).thenReturn(promiseAuthConfig.future)
      when(authFactory.resolverFunc).thenReturn(resolverFunc)
      authFactory

    /**
      * Returns a resolver function for credentials that operates on the
      * credentials that can be set for this helper instance.
      *
      * @return the credentials resolver function
      */
    private def createResolverFunc(): Credentials.ResolverFunc =
      credential =>
        requestedCredentialsMap.put(credential, true)
        Option(credentials.get(credential)) match
          case Some(value) => Future.successful(value)
          case None => Future.failed(new IllegalArgumentException("No credential: " + credential))
