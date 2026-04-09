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
import de.oliver_heger.linedj.archive.server.MediaFileResolver.UnresolvableFileException
import de.oliver_heger.linedj.archive.server.{ArchiveController, ArchiveServerConfig}
import de.oliver_heger.linedj.archive.server.content.ArchiveContentActor
import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.server.common.ServerController
import de.oliver_heger.linedj.shared.actors.ManagingActorFactory
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.apache.commons.configuration2.builder.fluent.Configurations
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.http.scaladsl.model.{HttpResponse, StatusCodes}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future

/**
  * Test class for [[Controller]].
  */
class ControllerSpec(testSystem: ActorSystem) extends TestKit(testSystem), AsyncFlatSpecLike, BeforeAndAfterAll,
  Matchers, MockitoSugar:
  def this() = this(ActorSystem("ControllerSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  "The config loader" should "correctly parse the configuration" in :
    val testConfigUrl = getClass.getResource("/cloud-archive-server-config.xml")
    val configs = new Configurations
    val serverConfig = configs.xml(testConfigUrl)

    val controller = new Controller with SystemPropertyAccess {}
    Future.fromTry(controller.configLoader(serverConfig)) map : archiveConfig =>
      archiveConfig.cacheDirectory.toString should be("/tmp/cloud-archives/cache")
      archiveConfig.archives should have size 4

  /**
    * Creates a context object with the given archive manager.
    *
    * @param archiveManager the archive manager
    * @return the server context
    */
  private def createServerContext(archiveManager: CloudArchiveManager):
  ArchiveController.ArchiveServerContext[CloudArchiveServerConfig, Controller.CloudArchiveServerContext] =
    val customContext = Controller.CloudArchiveServerContext(
      archiveManager = archiveManager,
      credentialsManager = mock
    )
    ArchiveController.ArchiveServerContext(
      serverConfig = mock[ArchiveServerConfig[CloudArchiveServerConfig]],
      contentActor = mock[ActorRef[ArchiveContentActor.ArchiveContentCommand]],
      customContext = customContext
    )

  /**
    * Provides an object with services that is required by some methods of the
    * controller.
    */
  private given ServerController.ServerServices =
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
