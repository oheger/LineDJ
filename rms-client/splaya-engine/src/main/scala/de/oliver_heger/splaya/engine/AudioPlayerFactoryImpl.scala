package de.oliver_heger.splaya.engine;
import scala.reflect.BeanProperty
import org.apache.commons.lang3.time.StopWatch
import org.apache.commons.vfs2.FileSystemManager
import de.oliver_heger.splaya.engine.io.SourceBufferManagerImpl
import de.oliver_heger.splaya.engine.io.SourceResolverImpl
import de.oliver_heger.splaya.engine.io.SourceStreamWrapperFactoryImpl
import de.oliver_heger.splaya.engine.io.TempFileFactoryImpl
import de.oliver_heger.splaya.engine.msg.EventTranslatorActor
import de.oliver_heger.splaya.engine.msg.Gateway
import de.oliver_heger.splaya.playlist.impl.AudioSourceDataExtractorActor
import de.oliver_heger.splaya.playlist.impl.AudioSourceDataExtractorImpl
import de.oliver_heger.splaya.playlist.impl.FSScannerImpl
import de.oliver_heger.splaya.playlist.impl.PlaylistControllerImpl
import de.oliver_heger.splaya.playlist.impl.PlaylistCtrlActor
import de.oliver_heger.splaya.playlist.impl.PlaylistDataExtractorActor
import de.oliver_heger.splaya.playlist.PlaylistFileStore
import de.oliver_heger.splaya.playlist.PlaylistGenerator
import de.oliver_heger.splaya.AudioPlayer
import de.oliver_heger.splaya.AudioPlayerFactory
import de.oliver_heger.splaya.osgiutil.ServiceWrapper
import de.oliver_heger.splaya.fs.FSService

/**
 * A factory class for constructing an [[de.oliver_heger.splaya.AudioPlayer]]
 * object.
 *
 * @param playlistFileStore the file store storing playlist meta data
 * @param fileSystemManager the VFS file system manager
 */
class AudioPlayerFactoryImpl(@BeanProperty val playlistFileStore: PlaylistFileStore,
  @BeanProperty val fileSystemManager: FileSystemManager)
  extends AudioPlayerFactory {
  /** The size of the temporary buffer used by the streaming actor. */
  @BeanProperty var bufferSize = 10 * 1024 * 1024

  /**
   * Creates a new ''AudioPlayer'' instance.
   * @return the ''AudioPlayer'' instance
   */
  def createAudioPlayer(): AudioPlayer = {
    //TODO obtain through dependency injection
    val fsService = new ServiceWrapper[FSService]
    val gateway = new Gateway
    val sourceResolver = new SourceResolverImpl(fileSystemManager)
    val tempFileFactory = new TempFileFactoryImpl
    val bufferManager = new SourceBufferManagerImpl(gateway)
    val ctxFactory = new PlaybackContextFactoryImpl
    val streamFactory = new SourceStreamWrapperFactoryImpl(bufferManager,
      tempFileFactory)
    val extractor = new AudioSourceDataExtractorImpl(fsService)
    //TODO provide meaningful implementation
    val plGenerator = new PlaylistGenerator {
      def generatePlaylist(songs: Seq[String], mode: String, params: xml.NodeSeq) =
        songs
    }

    val readActor = new SourceReaderActor(gateway, fsService,
      tempFileFactory, bufferSize / 2)
    val playbackActor = new PlaybackActor(gateway, ctxFactory, streamFactory)
    val lineActor = new LineWriteActor(gateway)
    val timingActor = new TimingActor(gateway, new StopWatch)
    val eventActor = new EventTranslatorActor(gateway, 4)
    val extrActor = new AudioSourceDataExtractorActor(extractor)
    val playlistExtrActor = new PlaylistDataExtractorActor(gateway, extrActor)
    val plCtrlActor = new PlaylistCtrlActor(gateway, readActor, fsService,
      playlistFileStore, plGenerator, Set("mp3"))

    gateway.start()
    readActor.start()
    playbackActor.start()
    lineActor.start()
    timingActor.start()
    eventActor.start()
    extrActor.start()
    playlistExtrActor.start()
    plCtrlActor.start()
    gateway += Gateway.ActorSourceRead -> readActor
    gateway += Gateway.ActorPlayback -> playbackActor
    gateway += Gateway.ActorLineWrite -> lineActor
    gateway.register(timingActor)
    gateway.register(eventActor)
    gateway.register(playlistExtrActor)
    gateway.register(plCtrlActor)

    val plCtrl = new PlaylistControllerImpl(plCtrlActor)
    new AudioPlayerImpl(gateway, plCtrl, timingActor, eventActor)
  }
}
