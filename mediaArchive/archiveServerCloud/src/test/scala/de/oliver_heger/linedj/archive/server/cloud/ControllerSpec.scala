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

package de.oliver_heger.linedj.archive.server.cloud

import com.github.cloudfiles.core.http.HttpRequestSender.FailedResponseException
import com.github.cloudfiles.core.http.factory.HttpRequestSenderFactoryImpl
import de.oliver_heger.linedj.archive.cloud.auth.Credentials.queryCredentialTimeout
import de.oliver_heger.linedj.archive.cloud.auth.oauth.OAuthStorageServiceImpl
import de.oliver_heger.linedj.archive.cloud.auth.{BasicAuthMethod, Credentials, DefaultAuthConfigFactory}
import de.oliver_heger.linedj.archive.cloud.{CloudFileDownloaderFactory, DefaultCloudFileDownloaderFactory}
import de.oliver_heger.linedj.archive.server.MediaFileResolver.UnresolvableFileException
import de.oliver_heger.linedj.archive.server.content.ArchiveContentActor
import de.oliver_heger.linedj.archive.server.model.{ArchiveCommands, ArchiveModel}
import de.oliver_heger.linedj.archive.server.{ArchiveController, ArchiveServerConfig}
import de.oliver_heger.linedj.server.common.{ConfigSupport, ServerConfig, ServerController}
import de.oliver_heger.linedj.shared.actors.{ActorFactory, ManagingActorFactory}
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.apache.commons.configuration2.builder.fluent.Configurations
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.http.scaladsl.model.{HttpResponse, StatusCodes}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

object ControllerSpec:
  /** A test server configuration. */
  private val TestConfig = CloudArchiveServerConfig(
    credentialsDirectory = Paths.get("credentials", "path"),
    cacheDirectory = Paths.get("local", "cache", "path"),
    archives = List(
      ArchiveConfig(
        archiveBaseUri = "https://archive.example.com/test",
        archiveName = "SomeTestArchive",
        authMethod = BasicAuthMethod("test-realm"),
        fileSystem = "OneDrive",
        optContentPath = None,
        optMediaPath = None,
        optMetadataPath = None,
        optParallelism = Some(5),
        optMaxContentSize = Some(115),
        optRequestQueueSize = None,
        optTimeout = None,
        optCryptConfig = None
      )
    )
  )
end ControllerSpec

/**
  * Test class for [[Controller]].
  */
class ControllerSpec(testSystem: ActorSystem) extends TestKit(testSystem), AsyncFlatSpecLike, BeforeAndAfterAll,
  Matchers, MockitoSugar:
  def this() = this(ActorSystem("ControllerSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import ControllerSpec.*

  "The config loader" should "correctly parse the configuration" in :
    val controller = new Controller with SystemPropertyAccess {}
    val testConfigUrl = getClass.getResource("/" + controller.defaultConfigFileName)
    val configs = new Configurations
    val serverConfig = configs.xml(testConfigUrl)

    Future.fromTry(controller.archiveConfigLoader(serverConfig)) map : archiveConfig =>
      archiveConfig.cacheDirectory.toString should be("/tmp/cloud-archives/cache")
      archiveConfig.archives should have size 4

  /**
    * Creates a server context with default values and the given custom
    * context.
    *
    * @param customContext the custom context
    * @tparam C the type of the custom context
    * @return the server context
    */
  private def createBaseContext[C](customContext: C):
  ArchiveController.ArchiveServerContext[CloudArchiveServerConfig, C] =
    ArchiveController.ArchiveServerContext(
      config = ArchiveServerConfig(50.seconds, TestConfig),
      contentActor = mock[ActorRef[ArchiveContentActor.ArchiveContentCommand]],
      archiveContext = customContext
    )

  /**
    * Creates a context object for the archive server with the given archive 
    * manager.
    *
    * @param archiveManager the archive manager
    * @return the server context
    */
  private def createArchiveServerContext(archiveManager: CloudArchiveManager):
  ArchiveController.ArchiveServerContext[CloudArchiveServerConfig, Controller.CloudArchiveServerContext] =
    val customContext = Controller.CloudArchiveServerContext(
      archiveManager = archiveManager,
      credentialsManager = mock
    )
    createBaseContext(customContext)

  private def createServerContext(archiveManager: CloudArchiveManager) =
    ConfigSupport.ConfigSupportContext(
      serverConfig = ServerConfig(8888, None),
      config = ArchiveServerConfig(30.seconds, TestConfig),
      context = createArchiveServerContext(archiveManager)
    )

  /**
    * Provides an object with services that is required by some methods of the
    * controller.
    */
  private given services: ServerController.ServerServices =
    ServerController.ServerServices(system, ManagingActorFactory.newDefaultManagingActorFactory)

  "The resolver function" should "resolve a file in an existing archive" in :
    val archiveManager = mock[CloudArchiveManager]
    val downloader = mock[ContentDownloader]
    val fileSource = mock[Source[ByteString, Any]]
    val downloadInfo = ArchiveModel.MediaFileDownloadInfo(
      fileUri = MediaFileUri("/test-medium/test-song.mp3"),
      archiveName = "testArchive"
    )
    val states = Map(
      "someArchive" -> CloudArchiveManager.CloudArchiveState.Waiting,
      downloadInfo.archiveName -> CloudArchiveManager.CloudArchiveState.Loaded(downloader)
    )
    when(archiveManager.archivesState).thenReturn(Future.successful(CloudArchiveManager.ArchivesState(states)))
    when(downloader.loadMediaFile(downloadInfo.fileUri)).thenReturn(Future.successful(fileSource))
    val context = createServerContext(archiveManager)

    val controller = new Controller with SystemPropertyAccess {}
    val resolver = controller.fileResolverFunc(context)

    resolver("some-id", downloadInfo) map : resolvedSource =>
      resolvedSource should be(fileSource)

  it should "handle a non-existing archive" in :
    val archiveManager = mock[CloudArchiveManager]
    val downloadInfo = ArchiveModel.MediaFileDownloadInfo(
      fileUri = MediaFileUri("/test-medium/test-song.mp3"),
      archiveName = "testArchive"
    )
    val states = Map("someArchive" -> CloudArchiveManager.CloudArchiveState.Waiting)
    when(archiveManager.archivesState).thenReturn(Future.successful(CloudArchiveManager.ArchivesState(states)))
    val context = createServerContext(archiveManager)

    val controller = new Controller with SystemPropertyAccess {}
    val resolver = controller.fileResolverFunc(context)

    recoverToSucceededIf[NoSuchElementException]:
      resolver("some-id", downloadInfo)


  it should "handle an archive that is not in Loaded state" in :
    val archiveManager = mock[CloudArchiveManager]
    val downloadInfo = ArchiveModel.MediaFileDownloadInfo(
      fileUri = MediaFileUri("/test-medium/test-song.mp3"),
      archiveName = "testArchive"
    )
    val states = Map(
      "someArchive" -> CloudArchiveManager.CloudArchiveState.Waiting,
      downloadInfo.archiveName -> CloudArchiveManager.CloudArchiveState.Waiting
    )
    when(archiveManager.archivesState).thenReturn(Future.successful(CloudArchiveManager.ArchivesState(states)))
    val context = createServerContext(archiveManager)

    val controller = new Controller with SystemPropertyAccess {}
    val resolver = controller.fileResolverFunc(context)

    recoverToSucceededIf[IllegalStateException]:
      resolver("some-id", downloadInfo)

  it should "handle a failed future from the downloader" in :
    val exception = FailedResponseException(HttpResponse())
    val archiveManager = mock[CloudArchiveManager]
    val downloader = mock[ContentDownloader]
    val downloadInfo = ArchiveModel.MediaFileDownloadInfo(
      fileUri = MediaFileUri("/test-medium/failure-song.mp3"),
      archiveName = "testArchive"
    )
    val states = Map(downloadInfo.archiveName -> CloudArchiveManager.CloudArchiveState.Loaded(downloader))
    when(archiveManager.archivesState).thenReturn(Future.successful(CloudArchiveManager.ArchivesState(states)))
    when(downloader.loadMediaFile(downloadInfo.fileUri)).thenReturn(Future.failed(exception))
    val context = createServerContext(archiveManager)

    val controller = new Controller with SystemPropertyAccess {}
    val resolver = controller.fileResolverFunc(context)

    recoverToExceptionIf[FailedResponseException]:
      resolver("failure-id", downloadInfo)
    .map: actualEx =>
      actualEx should be(exception)

  it should "handle a 404 response from the downloader" in :
    val response = HttpResponse(status = StatusCodes.NotFound)
    val exception = FailedResponseException(response)
    val fileID = "<ID of the unresolvable file>"
    val archiveManager = mock[CloudArchiveManager]
    val downloader = mock[ContentDownloader]
    val downloadInfo = ArchiveModel.MediaFileDownloadInfo(
      fileUri = MediaFileUri("/test-medium/non-existing-song.mp3"),
      archiveName = "testArchive"
    )
    val states = Map(downloadInfo.archiveName -> CloudArchiveManager.CloudArchiveState.Loaded(downloader))
    when(archiveManager.archivesState).thenReturn(Future.successful(CloudArchiveManager.ArchivesState(states)))
    when(downloader.loadMediaFile(downloadInfo.fileUri)).thenReturn(Future.failed(exception))
    val context = createServerContext(archiveManager)

    val controller = new Controller with SystemPropertyAccess {}
    val resolver = controller.fileResolverFunc(context)

    recoverToExceptionIf[UnresolvableFileException]:
      resolver(fileID, downloadInfo)
    .map: actualEx =>
      actualEx.fileID should be(fileID)

  /**
    * Creates a mock for the credentials manager that is prepared to return a 
    * mock credential setter and a mock resolver function.
    *
    * @return the mock credentials manager
    */
  private def createPreparedCredentialsManagerMock(): CloudArchiveCredentialsManager =
    val setter = mock[Credentials.CredentialSetter]
    val resolver = mock[Credentials.ResolverFunc]
    val manager = mock[CloudArchiveCredentialsManager]
    when(manager.setter).thenReturn(setter)
    when(manager.resolverFunc).thenReturn(resolver)
    manager

  "createArchiveContext()" should "create a correct credentials manager" in :
    val credentialsManager = createPreparedCredentialsManagerMock()
    val credentialsManagerFactory = mock[CloudArchiveCredentialsManager.Factory]
    val archiveManagerFactory = mock[CloudArchiveManager.Factory]
    when(
      archiveManagerFactory.apply(any(), any(), any(), any(), any(), any(), any())(using any())
    ).thenReturn(mock[CloudArchiveManager])
    when(credentialsManagerFactory.apply(TestConfig.credentialsDirectory, services.managingActorFactory))
      .thenReturn(credentialsManager)

    val controller = new Controller(credentialsManagerFactory, archiveManagerFactory) with SystemPropertyAccess {}
    controller.createArchiveContext(createBaseContext(())) map : customContext =>
      customContext.credentialsManager should be(credentialsManager)

  it should "create a correct downloader factory" in :
    val archiveManager = mock[CloudArchiveManager]
    val archiveManagerFactory = mock[CloudArchiveManager.Factory]
    val credentialsManagerFactory = mock[CloudArchiveCredentialsManager.Factory]
    val credentialsManager = createPreparedCredentialsManagerMock()
    when(credentialsManagerFactory.apply(TestConfig.credentialsDirectory, services.managingActorFactory))
      .thenReturn(credentialsManager)
    when(
      archiveManagerFactory.apply(any(), any(), any(), any(), any(), any(), any())(using any())
    ).thenReturn(archiveManager)

    val controller = new Controller(credentialsManagerFactory, archiveManagerFactory) with SystemPropertyAccess {}
    controller.createArchiveContext(createBaseContext(())) map : customContext =>
      val captDownloaderFactory = ArgumentCaptor.forClass(classOf[CloudFileDownloaderFactory])

      verify(archiveManagerFactory).apply(
        any(),
        any(),
        any(),
        captDownloaderFactory.capture(),
        any(),
        any(),
        any()
      )(using any())
      captDownloaderFactory.getValue match
        case downloaderFactory: DefaultCloudFileDownloaderFactory =>
          downloaderFactory.requestSenderFactory should be(HttpRequestSenderFactoryImpl)
          downloaderFactory.authConfigFactory match
            case authFactory: DefaultAuthConfigFactory =>
              authFactory.storageService should be(OAuthStorageServiceImpl)
              authFactory.storagePath should be(TestConfig.credentialsDirectory)
              authFactory.resolverFunc should be(credentialsManager.resolverFunc)
            case f => fail("Unexpected auth factory: " + f)
        case f => fail("Unexpected downloader factory: " + f)

  it should "create a correct archive manager" in :
    val baseContext = createBaseContext(())
    val credentialsManager = createPreparedCredentialsManagerMock()
    val archiveManager = mock[CloudArchiveManager]
    val archiveManagerFactory = new CloudArchiveManager.Factory:
      override def apply(actorFactory: ActorFactory,
                         contentActor: ActorRef[ArchiveCommands.UpdateArchiveContentCommand],
                         config: CloudArchiveServerConfig,
                         downloaderFactory: CloudFileDownloaderFactory,
                         credentialSetter: Credentials.CredentialSetter,
                         contentLoader: CloudArchiveContentLoader,
                         cacheFactory: CloudArchiveCache.Factory)
                        (using actorSystem: ActorSystem): CloudArchiveManager =
        actorFactory should be(services.managingActorFactory)
        contentActor should be(baseContext.contentActor)
        config should be(TestConfig)
        credentialSetter should be(credentialsManager.setter)
        contentLoader should not be null
        cacheFactory should be(CloudArchiveCache.newInstance)
        actorSystem should be(system)
        archiveManager

    val credentialsManagerFactory = mock[CloudArchiveCredentialsManager.Factory]
    when(credentialsManagerFactory.apply(TestConfig.credentialsDirectory, services.managingActorFactory))
      .thenReturn(credentialsManager)

    val controller = new Controller(credentialsManagerFactory, archiveManagerFactory) with SystemPropertyAccess {}
    controller.createArchiveContext(baseContext) map : customContext =>
      customContext.archiveManager should be(archiveManager)
