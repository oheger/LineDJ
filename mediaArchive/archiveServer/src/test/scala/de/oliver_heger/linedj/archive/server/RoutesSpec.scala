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

package de.oliver_heger.linedj.archive.server

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.server.content.ArchiveContentActor
import de.oliver_heger.linedj.archive.server.model.{ArchiveCommands, ArchiveModel}
import de.oliver_heger.linedj.server.common.ServerController
import de.oliver_heger.linedj.shared.actors.ManagingActorFactory
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.{ContentDispositionTypes, `Content-Disposition`}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, OptionValues}

import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import scala.collection.immutable.Seq
import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt

object RoutesSpec:
  /** The configuration used by tests per default. */
  private val TestServerConfig = ArchiveServerConfig(
    0,
    ArchiveServerConfig.DefaultServerTimeout,
    Seq.empty[MediaArchiveConfig]
  )

  /** The ID of a test medium. */
  private val TestMediumID = Checksums.MediumChecksum("test-medium-id")

  /** The ID of a test media file. */
  private val TestMediaFileID = "test-file-id"

  /** The name of the test MP3 file that is used to test skipping ID3 data. */
  private val TestMp3File = "/testID3v2Data.mp3"

  /** The relative URI of the test MP3 file. */
  private val TestMp3Uri = MediaFileUri("/medium/artist/album/song.mp3")

  /** The actual data content of the test MP3 file. */
  private val TestMp3Data = "Lorem ipsum dolor sit amet, consetetur sadipscing " +
    "elitr, sed diam nonumy eirmod tempor invidunt ut labore et dolore " +
    "magna aliquyam erat, sed diam voluptua. At vero eos et accusam et justo duo"
end RoutesSpec

/**
  * Test class for the routes of the archive server.
  */
class RoutesSpec extends AnyFlatSpec with BeforeAndAfterAll with Matchers with ScalatestRouteTest
  with OptionValues with MediaFileTestSupport with ArchiveModel.ArchiveJsonSupport:
  /** The test kit for typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit =
    testKit.shutdownTestKit()
    super.afterAll()

  import RoutesSpec.*

  /**
    * Returns a [[Route]] to be tested based on the given content actor. This
    * function obtains the route from the [[Controller]], so that the 
    * controller method creating the route is tested as well.
    *
    * @param contentActor the content actor to use
    * @return the [[Route]] for being tested
    */
  private def testRoute(contentActor: ActorRef[ArchiveCommands.ArchiveQueryCommand],
                        config: ArchiveServerConfig[Seq[MediaArchiveConfig]] = TestServerConfig): Route =
    val controller = new Controller() with SystemPropertyAccess {}
    val context = Controller.ArchiveServerContext(
      serverConfig = config,
      contentActor = contentActor
    )
    val services = ServerController.ServerServices(system, ManagingActorFactory.newDefaultManagingActorFactory)
    val shutdownPromise = Promise[Done]()
    controller.route(context, shutdownPromise)(using services)

  /**
    * Copies the test MP3 file to the folder structure of the given media
    * archive.
    *
    * @param archiveConfig the config of the target media archive
    * @return the path of the test file in this archive
    */
  private def copyMp3TestFile(archiveConfig: MediaArchiveConfig): Path =
    val fileUrl = getClass.getResource(TestMp3File)
    fileUrl should not be null
    val filePath = Paths.get(fileUrl.toURI)
    val targetPath = writeMediaFile(archiveConfig, Paths.get(TestMp3Uri.path), "")
    Files.copy(filePath, targetPath, StandardCopyOption.REPLACE_EXISTING)

  /**
    * Returns a behavior for a stub content actor that expects and handles a
    * request for download information for the test media file. The request is
    * answered with the specified download information.
    *
    * @param downloadInfo the download information
    * @return the behavior of the stub actor
    */
  private def behaviorForDownloadRequest(downloadInfo: ArchiveModel.MediaFileDownloadInfo):
  Behavior[ArchiveContentActor.ArchiveContentCommand] =
    Behaviors.receiveMessagePartial[ArchiveContentActor.ArchiveContentCommand]:
      case ArchiveCommands.ReadArchiveContentCommand.GetFileDownloadInfo(fileID, replyTo)
        if fileID == TestMediaFileID =>
        replyTo ! ArchiveCommands.GetFileResponse(fileID = fileID, optResult = Some(downloadInfo))
        Behaviors.stopped

  "Routes" should "define a route for obtaining an overview over all media" in :
    val mediaOverview = List(
      ArchiveModel.MediumOverview(Checksums.MediumChecksum("c1"), "testMedium1"),
      ArchiveModel.MediumOverview(Checksums.MediumChecksum("c2"), "testMedium2"),
      ArchiveModel.MediumOverview(Checksums.MediumChecksum("c3"), "testMedium3")
    )
    val contentBehavior = Behaviors.receiveMessagePartial[ArchiveCommands.ArchiveQueryCommand]:
      case ArchiveCommands.ReadArchiveContentCommand.GetMedia(replyTo) =>
        replyTo ! ArchiveCommands.GetMediaResponse(mediaOverview)
        Behaviors.same

    val contentActor = testKit.spawn(contentBehavior)
    Get("/api/archive/media") ~> testRoute(contentActor) ~> check:
      status should be(StatusCodes.OK)
      val actualMedia = responseAs[ArchiveModel.MediaOverview]
      actualMedia.media should contain theSameElementsAs mediaOverview

  it should "respect the configured timeout" in :
    val config = ArchiveServerConfig(
      serverPort = 8080,
      timeout = 10.millis,
      archiveConfig = Seq.empty[MediaArchiveConfig]
    )
    val contentBehavior = Behaviors.receivePartial[ArchiveContentActor.ArchiveContentCommand]:
      case (context, ArchiveCommands.ReadArchiveContentCommand.GetMedia(replyTo)) =>
        context.scheduleOnce(500.millis, replyTo, ArchiveCommands.GetMediaResponse(Nil))
        Behaviors.same

    val contentActor = testKit.spawn(contentBehavior)
    Get("/api/archive/media") ~> testRoute(contentActor, config) ~> check:
      status should be(StatusCodes.InternalServerError)

  it should "define a route to query the details of a medium" in :
    val medium = ArchiveModel.MediumDetails(
      overview = ArchiveModel.MediumOverview(TestMediumID, "someTestMedium"),
      description = "This is a test medium",
      orderMode = Some(ArchiveModel.OrderMode.Medium),
      archiveName = "someTestArchive"
    )
    val contentBehavior = Behaviors.receiveMessagePartial[ArchiveContentActor.ArchiveContentCommand]:
      case ArchiveCommands.ReadArchiveContentCommand.GetMedium(id, replyTo) if id == medium.id =>
        replyTo ! ArchiveCommands.GetMediumResponse(id, Some(medium))
        Behaviors.same

    val contentActor = testKit.spawn(contentBehavior)
    Get(s"/api/archive/media/${TestMediumID.checksum}") ~> testRoute(contentActor) ~> check:
      status should be(StatusCodes.OK)
      val actualMedium = responseAs[ArchiveModel.MediumDetails]
      actualMedium should be(medium)

  it should "handle a request for the details of a non-existing medium" in :
    val contentBehavior = Behaviors.receiveMessagePartial[ArchiveContentActor.ArchiveContentCommand]:
      case ArchiveCommands.ReadArchiveContentCommand.GetMedium(id, replyTo) if id == TestMediumID =>
        replyTo ! ArchiveCommands.GetMediumResponse(id, None)
        Behaviors.same

    val contentActor = testKit.spawn(contentBehavior)
    Get(s"/api/archive/media/${TestMediumID.checksum}") ~> testRoute(contentActor) ~> check:
      status should be(StatusCodes.NotFound)

  it should "define a route to query the artists contained on a medium" in :
    val artists = List(
      ArchiveModel.ArtistInfo("art1", "The artist formally known as..."),
      ArchiveModel.ArtistInfo("art2", "Some other artist"),
      ArchiveModel.ArtistInfo("art3", "Another artist")
    )
    val contentBehavior = Behaviors.receiveMessagePartial[ArchiveContentActor.ArchiveContentCommand]:
      case req@ArchiveCommands.ReadMediumContentCommand.GetArtists(id, replyTo) if id == TestMediumID =>
        replyTo ! ArchiveCommands.GetMediumDataResponse(req, Some(artists))
        Behaviors.stopped

    val contentActor = testKit.spawn(contentBehavior)
    Get(s"/api/archive/media/${TestMediumID.checksum}/artists") ~> testRoute(contentActor) ~> check:
      status should be(StatusCodes.OK)
      val artistData = responseAs[ArchiveModel.ItemsResult[ArchiveModel.ArtistInfo]]
      artistData.items should contain theSameElementsInOrderAs artists

  it should "handle a request for the artists of a non-existing medium" in :
    val contentBehavior = Behaviors.receiveMessagePartial[ArchiveContentActor.ArchiveContentCommand]:
      case req@ArchiveCommands.ReadMediumContentCommand.GetArtists(id, replyTo) if id == TestMediumID =>
        replyTo ! ArchiveCommands.GetMediumDataResponse(req, None)
        Behaviors.stopped

    val contentActor = testKit.spawn(contentBehavior)
    Get(s"/api/archive/media/${TestMediumID.checksum}/artists") ~> testRoute(contentActor) ~> check:
      status should be(StatusCodes.NotFound)

  it should "define a route to query the albums contained on a medium" in :
    val albums = List(
      ArchiveModel.AlbumInfo("alb1", "Brothers in arms"),
      ArchiveModel.AlbumInfo("alb2", "Tales of mystery and imaginations"),
      ArchiveModel.AlbumInfo("alb3", "Tubular bells"),
    )

    val contentBehavior = Behaviors.receiveMessagePartial[ArchiveContentActor.ArchiveContentCommand]:
      case req@ArchiveCommands.ReadMediumContentCommand.GetAlbums(id, replyTo) if id == TestMediumID =>
        replyTo ! ArchiveCommands.GetMediumDataResponse(req, Some(albums))
        Behaviors.stopped

    val contentActor = testKit.spawn(contentBehavior)
    Get(s"/api/archive/media/${TestMediumID.checksum}/albums") ~> testRoute(contentActor) ~> check:
      status should be(StatusCodes.OK)
      val artistData = responseAs[ArchiveModel.ItemsResult[ArchiveModel.AlbumInfo]]
      artistData.items should contain theSameElementsInOrderAs albums

  it should "handle a request for the albums of a non-existing medium" in :
    val contentBehavior = Behaviors.receiveMessagePartial[ArchiveContentActor.ArchiveContentCommand]:
      case req@ArchiveCommands.ReadMediumContentCommand.GetAlbums(id, replyTo) if id == TestMediumID =>
        replyTo ! ArchiveCommands.GetMediumDataResponse(req, None)
        Behaviors.stopped

    val contentActor = testKit.spawn(contentBehavior)
    Get(s"/api/archive/media/${TestMediumID.checksum}/albums") ~> testRoute(contentActor) ~> check:
      status should be(StatusCodes.NotFound)

  it should "define a route to query the songs of an artist from a medium" in :
    val songs = List(
      MediaMetadata(title = Some("test song 1"), size = 2124, checksum = "chk-song-1"),
      MediaMetadata(title = Some("test song 2"), size = 2125, checksum = "chk-song-2"),
      MediaMetadata(title = Some("test song 3"), size = 2126, checksum = "chk-song-3")
    )
    val ArtistID = "test-artist"

    val contentBehavior = Behaviors.receiveMessagePartial[ArchiveContentActor.ArchiveContentCommand]:
      case req@ArchiveCommands.ReadMediumContentCommand.GetSongsForArtist(mediumID, artistID, replyTo) =>
        mediumID should be(TestMediumID)
        artistID should be(ArtistID)
        replyTo ! ArchiveCommands.GetMediumDataResponse(req, Some(songs))
        Behaviors.stopped

    val contentActor = testKit.spawn(contentBehavior)
    Get(s"/api/archive/media/${TestMediumID.checksum}/artists/$ArtistID/songs") ~> testRoute(contentActor) ~> check:
      status should be(StatusCodes.OK)
      val songData = responseAs[ArchiveModel.ItemsResult[MediaMetadata]]
      songData.items should contain theSameElementsInOrderAs songs

  it should "handle a failed result for the songs of an artist from a medium" in :
    val ArtistID = "missing-artist"
    val contentBehavior = Behaviors.receiveMessagePartial[ArchiveContentActor.ArchiveContentCommand]:
      case req@ArchiveCommands.ReadMediumContentCommand.GetSongsForArtist(mediumID, artistID, replyTo) =>
        mediumID should be(TestMediumID)
        artistID shouldBe ArtistID
        replyTo ! ArchiveCommands.GetMediumDataResponse(req, None)
        Behaviors.stopped

    val contentActor = testKit.spawn(contentBehavior)
    Get(s"/api/archive/media/${TestMediumID.checksum}/artists/$ArtistID/songs") ~> testRoute(contentActor) ~> check:
      status should be(StatusCodes.NotFound)

  it should "define a route to query the songs of an album from a medium" in :
    val songs = List(
      MediaMetadata(title = Some("test song 1"), size = 2124, checksum = "chk-song-1"),
      MediaMetadata(title = Some("test song 2"), size = 2125, checksum = "chk-song-2"),
      MediaMetadata(title = Some("test song 3"), size = 2126, checksum = "chk-song-3")
    )
    val AlbumID = "test-album"

    val contentBehavior = Behaviors.receiveMessagePartial[ArchiveContentActor.ArchiveContentCommand]:
      case req@ArchiveCommands.ReadMediumContentCommand.GetSongsForAlbum(mediumID, albumID, replyTo) =>
        mediumID should be(TestMediumID)
        albumID should be(AlbumID)
        replyTo ! ArchiveCommands.GetMediumDataResponse(req, Some(songs))
        Behaviors.stopped

    val contentActor = testKit.spawn(contentBehavior)
    Get(s"/api/archive/media/${TestMediumID.checksum}/albums/$AlbumID/songs") ~> testRoute(contentActor) ~> check:
      status should be(StatusCodes.OK)
      val songData = responseAs[ArchiveModel.ItemsResult[MediaMetadata]]
      songData.items should contain theSameElementsInOrderAs songs

  it should "handle a failed result for the songs of an album from a medium" in :
    val AlbumID = "missing-artist"
    val contentBehavior = Behaviors.receiveMessagePartial[ArchiveContentActor.ArchiveContentCommand]:
      case req@ArchiveCommands.ReadMediumContentCommand.GetSongsForAlbum(mediumID, albumID, replyTo) =>
        mediumID should be(TestMediumID)
        albumID shouldBe AlbumID
        replyTo ! ArchiveCommands.GetMediumDataResponse(req, None)
        Behaviors.stopped

    val contentActor = testKit.spawn(contentBehavior)
    Get(s"/api/archive/media/${TestMediumID.checksum}/albums/$AlbumID/songs") ~> testRoute(contentActor) ~> check:
      status should be(StatusCodes.NotFound)

  it should "define a route to query the albums of an artist" in :
    val ArtistID = "art_dire_straits"
    val albums = List(
      ArchiveModel.AlbumInfo("alb1", "Brothers in arms"),
      ArchiveModel.AlbumInfo("alb2", "Dire Straits"),
      ArchiveModel.AlbumInfo("alb3", "Love over gold"),
    )

    val contentBehavior = Behaviors.receiveMessagePartial[ArchiveContentActor.ArchiveContentCommand]:
      case req@ArchiveCommands.ReadMediumContentCommand.GetAlbumsForArtist(id, artistID, replyTo) =>
        id should be(TestMediumID)
        artistID should be(ArtistID)
        replyTo ! ArchiveCommands.GetMediumDataResponse(req, Some(albums))
        Behaviors.stopped

    val contentActor = testKit.spawn(contentBehavior)
    Get(s"/api/archive/media/${TestMediumID.checksum}/artists/$ArtistID/albums") ~> testRoute(contentActor) ~> check:
      status should be(StatusCodes.OK)
      val artistData = responseAs[ArchiveModel.ItemsResult[ArchiveModel.AlbumInfo]]
      artistData.items should contain theSameElementsInOrderAs albums

  it should "handle a request for the albums of an artist on an unknown medium" in :
    val contentBehavior = Behaviors.receiveMessagePartial[ArchiveContentActor.ArchiveContentCommand]:
      case req@ArchiveCommands.ReadMediumContentCommand.GetAlbumsForArtist(_, _, replyTo) =>
        replyTo ! ArchiveCommands.GetMediumDataResponse(req, None)
        Behaviors.stopped

    val contentActor = testKit.spawn(contentBehavior)
    Get(s"/api/archive/media/${TestMediumID.checksum}/artists/someArtist/albums") ~> testRoute(contentActor) ~> check:
      status should be(StatusCodes.NotFound)

  it should "define a route to query information about a media file" in :
    val fileInfo = ArchiveModel.MediaFileInfo(
      metadata = MediaMetadata(title = Some("Test song"), size = 10000, checksum = TestMediaFileID),
      fileUri = MediaFileUri("some/path/to/test-song.mp3"),
      mediumID = TestMediumID
    )
    val contentBehavior = Behaviors.receiveMessagePartial[ArchiveContentActor.ArchiveContentCommand]:
      case ArchiveCommands.ReadArchiveContentCommand.GetFileInfo(fileID, replyTo) if fileID == TestMediaFileID =>
        replyTo ! ArchiveCommands.GetFileResponse(fileID = TestMediaFileID, optResult = Some(fileInfo))
        Behaviors.stopped

    val contentActor = testKit.spawn(contentBehavior)
    Get(s"/api/archive/files/$TestMediaFileID/info") ~> testRoute(contentActor) ~> check:
      status should be(StatusCodes.OK)
      val fileResponse = responseAs[ArchiveModel.MediaFileInfo]
      fileResponse should be(fileInfo)

  it should "ignore requests for a fileID without another path segment" in :
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    Get(s"/api/archive/files$TestMediaFileID") ~> testRoute(contentActor) ~> check:
      handled shouldBe false

  it should "handle a request for information about a non-existing file" in :
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    Get(s"/api/archive/files/$TestMediaFileID/info") ~> testRoute(contentActor) ~> check:
      status should be(StatusCodes.NotFound)

  it should "define a route to download a media file" in :
    val archiveConfig = createArchiveConfigWithRootPath("myMusic")
    val downloadInfo = ArchiveModel.MediaFileDownloadInfo(
      archiveName = archiveConfig.archiveName,
      fileUri = MediaFileUri("/test-medium/test-artist/test-album/test-song.mp3")
    )
    writeMediaFile(archiveConfig, Paths.get(downloadInfo.fileUri.path), FileTestHelper.TestData)
    val serverConfig = TestServerConfig.copy(archiveConfig = Seq(archiveConfig))
    val contentBehavior = behaviorForDownloadRequest(downloadInfo)

    val contentActor = testKit.spawn(contentBehavior)
    Get(s"/api/archive/files/$TestMediaFileID/download") ~> testRoute(contentActor, serverConfig) ~> check:
      status should be(StatusCodes.OK)
      val fileData = responseAs[String]
      fileData should be(FileTestHelper.TestData)
      val contentDispoHeader = header[`Content-Disposition`].value
      contentDispoHeader.dispositionType should be(ContentDispositionTypes.attachment)
      contentDispoHeader.params("filename") should be(Paths.get(downloadInfo.fileUri.path).getFileName.toString)

  it should "handle a download request for a non-existing media file" in :
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    Get(s"/api/archive/files/$TestMediaFileID/download") ~> testRoute(contentActor) ~> check:
      status should be(StatusCodes.NotFound)

  it should "handle a download request for a non-resolvable media file" in :
    val archiveConfig = createArchiveConfigWithRootPath("myMusic")
    val downloadInfo = ArchiveModel.MediaFileDownloadInfo(
      archiveName = archiveConfig.archiveName,
      fileUri = MediaFileUri("/test-medium/test-artist/test-album/test-song.mp3")
    )
    val serverConfig = TestServerConfig.copy(archiveConfig = Seq(archiveConfig))
    val contentBehavior = behaviorForDownloadRequest(downloadInfo)

    val contentActor = testKit.spawn(contentBehavior)
    Get(s"/api/archive/files/$TestMediaFileID/download") ~> testRoute(contentActor, serverConfig) ~> check:
      status should be(StatusCodes.NotFound)

  /**
    * Checks a download request for the MP3 test file which should yield the
    * content of the file without ID3 metadata.
    *
    * @param skipParameter the value of the skip parameter
    */
  private def checkDownloadWithoutMetadata(skipParameter: String): Unit =
    val archiveConfig = createArchiveConfigWithRootPath("myMusicAndMeta")
    copyMp3TestFile(archiveConfig)
    val downloadInfo = ArchiveModel.MediaFileDownloadInfo(
      archiveName = archiveConfig.archiveName,
      fileUri = TestMp3Uri
    )
    val serverConfig = TestServerConfig.copy(archiveConfig = Seq(archiveConfig))
    val contentBehavior = behaviorForDownloadRequest(downloadInfo)

    val contentActor = testKit.spawn(contentBehavior)
    val requestUri = s"/api/archive/files/$TestMediaFileID/download?stripMetadata=$skipParameter"
    Get(requestUri) ~> testRoute(contentActor, serverConfig) ~> check:
      status should be(StatusCodes.OK)
      val fileData = responseAs[String]
      fileData should be(TestMp3Data)

  /**
    * Checks a download request for the MP3 test file which should yield the
    * full file, including ID3 metadata.
    *
    * @param requestUri the request URI to send to the archive
    */
  private def checkDownloadWithMetadata(requestUri: String): Unit =
    val archiveConfig = createArchiveConfigWithRootPath("myMusicAndMeta")
    copyMp3TestFile(archiveConfig)
    val downloadInfo = ArchiveModel.MediaFileDownloadInfo(
      archiveName = archiveConfig.archiveName,
      fileUri = TestMp3Uri
    )
    val serverConfig = TestServerConfig.copy(archiveConfig = Seq(archiveConfig))
    val contentBehavior = behaviorForDownloadRequest(downloadInfo)

    val contentActor = testKit.spawn(contentBehavior)
    Get(requestUri) ~> testRoute(contentActor, serverConfig) ~> check:
      status should be(StatusCodes.OK)
      val fileData = responseAs[String]
      fileData should have size 3273

  it should "handle a download request with skipped metadata" in :
    checkDownloadWithoutMetadata("true")

  it should "interpret the skip metadata parameter in a case-insensitive way" in :
    checkDownloadWithoutMetadata("TRUE")

  it should "not skip metadata if the parameter is missing" in :
    checkDownloadWithMetadata(s"/api/archive/files/$TestMediaFileID/download")

  it should "not skip metadata if the parameter has a non-true value" in :
    checkDownloadWithMetadata(s"/api/archive/files/$TestMediaFileID/download?stripMetadata=donotcare")
