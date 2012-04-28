package de.oliver_heger.splaya.engine;
import scala.reflect.BeanProperty

import org.apache.commons.lang3.time.StopWatch
import org.apache.commons.vfs2.VFS

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

/**
 * A factory class for constructing an [[de.oliver_heger.splaya.AudioPlayer]]
 * object.
 */
class AudioPlayerFactoryImpl {
  /** The size of the temporary buffer used by the streaming actor. */
  @BeanProperty var bufferSize = 10 * 1024 * 1024

  /** The file store for playlist meta data files. */
  @BeanProperty var playlistFileStore: PlaylistFileStore = _

  def createAudioPlayer(): AudioPlayer = {
    val fileSystemManager = VFS.getManager()
    val sourceResolver = new SourceResolverImpl(fileSystemManager)
    val tempFileFactory = new TempFileFactoryImpl
    val bufferManager = new SourceBufferManagerImpl
    val ctxFactory = new PlaybackContextFactoryImpl
    val streamFactory = new SourceStreamWrapperFactoryImpl(bufferManager,
      tempFileFactory)
    val extractor = new AudioSourceDataExtractorImpl(sourceResolver)
    val fsScanner = new FSScannerImpl(fileSystemManager)
    //TODO provide meaningful implementation
    val plGenerator = new PlaylistGenerator {
      def generatePlaylist(songs: Seq[String], mode: String, params: xml.NodeSeq) =
        songs
    }

    val readActor = new SourceReaderActor(sourceResolver, tempFileFactory,
      bufferSize / 2)
    val playbackActor = new PlaybackActor(ctxFactory, streamFactory)
    val lineActor = new LineWriteActor
    val timingActor = new TimingActor(new StopWatch)
    val eventActor = new EventTranslatorActor
    val extrActor = new AudioSourceDataExtractorActor(extractor)
    val playlistExtrActor = new PlaylistDataExtractorActor(extrActor)
    val plCtrlActor = new PlaylistCtrlActor(readActor, fsScanner,
      playlistFileStore, plGenerator)

    Gateway.start()
    readActor.start()
    playbackActor.start()
    lineActor.start()
    timingActor.start()
    eventActor.start()
    extrActor.start()
    playlistExtrActor.start()
    plCtrlActor.start()
    Gateway += Gateway.ActorSourceRead -> readActor
    Gateway += Gateway.ActorPlayback -> playbackActor
    Gateway += Gateway.ActorLineWrite -> lineActor
    Gateway.register(timingActor)
    Gateway.register(eventActor)
    Gateway.register(playlistExtrActor)
    Gateway.register(plCtrlActor)

    val plCtrl = new PlaylistControllerImpl(plCtrlActor)
    new AudioPlayerImpl(plCtrl, timingActor, eventActor)
  }
}
