package de.oliver_heger.splaya.engine
import scala.actors.Actor

import org.apache.commons.lang3.time.StopWatch

import de.oliver_heger.splaya.engine.io.SourceStreamWrapperFactory
import de.oliver_heger.splaya.engine.io.TempFileFactory
import de.oliver_heger.splaya.engine.msg.Gateway
import de.oliver_heger.splaya.fs.FSService
import de.oliver_heger.splaya.osgiutil.ServiceWrapper
import de.oliver_heger.splaya.playlist.impl.AudioSourceDataExtractor
import de.oliver_heger.splaya.playlist.impl.AudioSourceDataExtractorActor
import de.oliver_heger.splaya.playlist.impl.PlaylistCreationActor
import de.oliver_heger.splaya.playlist.impl.PlaylistCtrlActor
import de.oliver_heger.splaya.playlist.impl.PlaylistDataExtractorActor
import de.oliver_heger.splaya.playlist.PlaylistFileStore
import msg.EventTranslatorActor

/**
 * A trait which is used by
 * [[de.oliver_heger.splaya.engine.AudioPlayerFactoryImpl]] to create
 * concrete instances of specific actors.
 *
 * When creating a new audio player component, a number of specific actor
 * objects have to be created. This trait defines factory methods for these
 * specific actors. By delegating the creation of actors to a specialized
 * component, the audio player factory is less tightly coupled to concrete
 * actor implementations and becomes more testable.
 *
 * This implementation is already fully functional. All methods return
 * specialized actor implementations.
 */
trait ActorFactory {
  /**
   * Creates an actor for creating playlist instances.
   * @return the playlist creation actor
   */
  def createPlaylistCreationActor(): Actor = new PlaylistCreationActor

  /**
   * Creates an actor for reading data from the source medium.
   * @param gateway the gateway
   * @param fsService a reference to the ''FSService''
   * @param tempFileFactory the factory for temporary files
   * @param bufferSize the size of the buffer to reserve on the local hard disc
   * @return the source reader actor
   */
  def createSourceReaderActor(gateway: Gateway,
    fsService: ServiceWrapper[FSService], tempFileFactory: TempFileFactory,
    bufferSize: Int): Actor =
    new SourceReaderActor(gateway, fsService, tempFileFactory, bufferSize)

  /**
   * Creates the actor which handles playback.
   * @param gateway the gateway
   * @param ctxFactory the factory for creating a playback context
   * @param streamFactory the factory for creating stream wrappers
   * @return the playback actor
   */
  def createPlaybackActor(gateway: Gateway, ctxFactory: PlaybackContextFactory,
    streamFactory: SourceStreamWrapperFactory): Actor =
    new PlaybackActor(gateway, ctxFactory, streamFactory)

  /**
   * Creates the actor which manages the data line.
   * @param gateway the gateway
   * @return the line actor
   */
  def createLineActor(gateway: Gateway): Actor = new LineWriteActor(gateway)

  /**
   * Creates the actor responsible for measuring time.
   * @param gateway the gateway
   * @param watch the stop watch object
   * @return the timing actor
   */
  def createTimingActor(gateway: Gateway, watch: StopWatch): Actor =
    new TimingActor(gateway, watch)

  /**
   * Creates the actor responsible for sending events.
   * @param gateway the gateway
   * @param actorsToExit the number of actors that have to exit
   * @return the event translator actor
   */
  def createEventTranslatorActor(gateway: Gateway, actorsToExit: Int): Actor =
    new EventTranslatorActor(gateway, actorsToExit)

  /**
   * Creates the actor responsible for extracting meta data from audio files.
   * @param extr the actual meta data extractor object
   * @return the audio source data extractor actor
   */
  def createAudioSourceDataExtractorActor(extr: AudioSourceDataExtractor): Actor =
    new AudioSourceDataExtractorActor(extr)

  /**
   * Creates the actor responsible for extracting audio meta data for a whole
   * playlist.
   * @param gateway the gateway
   * @param extrActor the actor which handles meta data extraction for a single
   * audio file
   * @return the playlist data extractor actor
   */
  def createPlaylistDataExtractorActor(gateway: Gateway, extrActor: Actor): Actor =
    new PlaylistDataExtractorActor(gateway, extrActor)

  /**
   * Creates the actor which controls the playlist.
   * @param gateway the gateway
   * @param readActor the source reader actor
   * @param fsService a reference to the ''FSService''
   * @param playlistFileStore the object managing playlist information files
   * @param playlistCreationActor the actor managing the creation of new
   * playlist instances
   * @param extensions a set with the file extensions of supported audio files
   * @return the playlist controller actor
   */
  def createPlaylistCtrlActor(gateway: Gateway, readActor: Actor,
    fsService: ServiceWrapper[FSService], playlistFileStore: PlaylistFileStore,
    playlistCreationActor: Actor, extensions: Set[String]): Actor =
    new PlaylistCtrlActor(gateway, readActor, fsService, playlistFileStore,
      playlistCreationActor, extensions)
}
