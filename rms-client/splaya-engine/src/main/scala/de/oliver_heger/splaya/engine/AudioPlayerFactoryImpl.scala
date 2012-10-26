package de.oliver_heger.splaya.engine;
import java.util.Locale

import scala.Array.canBuildFrom

import org.apache.commons.lang3.time.StopWatch
import org.slf4j.LoggerFactory

import de.oliver_heger.splaya.engine.io.SourceBufferManagerImpl
import de.oliver_heger.splaya.engine.io.SourceStreamWrapperFactoryImpl
import de.oliver_heger.splaya.engine.io.TempFileFactoryImpl
import de.oliver_heger.splaya.engine.msg.Exit
import de.oliver_heger.splaya.engine.msg.Gateway
import de.oliver_heger.splaya.fs.FSService
import de.oliver_heger.splaya.osgiutil.ServiceWrapper
import de.oliver_heger.splaya.playlist.impl.AddMediaDataExtractor
import de.oliver_heger.splaya.playlist.impl.AddPlaylistGenerator
import de.oliver_heger.splaya.playlist.impl.AudioSourceDataExtractorImpl
import de.oliver_heger.splaya.playlist.impl.PlaylistControllerImpl
import de.oliver_heger.splaya.playlist.impl.PlaylistFileStoreImpl
import de.oliver_heger.splaya.playlist.impl.RemoveMediaDataExtractor
import de.oliver_heger.splaya.playlist.impl.RemovePlaylistGenerator
import de.oliver_heger.splaya.playlist.PlaylistFileStore
import de.oliver_heger.splaya.playlist.PlaylistGenerator
import de.oliver_heger.splaya.AudioPlayer
import de.oliver_heger.splaya.AudioPlayerFactory
import de.oliver_heger.splaya.MediaDataExtractor
import de.oliver_heger.splaya.PlaybackContextFactory

/**
 * A factory class for constructing an [[de.oliver_heger.splaya.AudioPlayer]]
 * object.
 *
 * This class is a declarative services component which provides an audio
 * player creation service. It consumes some services required by audio player
 * instances:
 * $ - [[de.oliver_heger.splaya.fs.FSServive]] for accessing the file system
 * containing the media files to be played
 * $ - [[de.oliver_heger.splaya.playlist.PlaylistGenerator]] generates ordered
 * playlists based on specific criteria; an arbitrary number of services of this
 * type can be bound to this component
 * $ - [[de.oliver_heger.splaya.playlist.MediaDataExtractor]] is used to obtain
 * meta information from audio files to be played; such services are optional,
 * an arbitrary number can be bound supporting different audio file formats
 *
 * Some configuration properties can be set in the declarative services
 * configuration:
 * $ - `audioPlayer.bufferSize`: the size of the playback buffer to reserve on
 * the local hard disk; this is a string with an optional unit; units can be
 * B or b for bytes, K or k for kilobytes, M or m for megabytes; if the unit is
 * missing, it is assumed that the size is provided in bytes
 * $ - `audioPlayer.fileExtensions`: a comma-separated list with file extensions
 * of media files to be taken into account
 *
 * For a new audio player object a number of different ''Actor'' objects has to
 * be created. This is done through an
 * [[de.oliver_heger.splaya.engine.ActorFactory]] object. It is possible to
 * pass such an object to the constructor, but this is mainly used for
 * testing purposes. A default actor factory is set by the default
 * constructor.
 *
 * @param actorFactory the factory for creating actor objects
 */
class AudioPlayerFactoryImpl(val actorFactory: ActorFactory)
  extends AudioPlayerFactory {
  /** The object for managing the file system service. */
  protected[engine] val fsService = new ServiceWrapper[FSService]

  /** The ''PlaylistFileStore'' implementation used by this factory. */
  protected[engine] val playlistFileStore: PlaylistFileStore =
    AudioPlayerFactoryImpl.createPlaylistFileStore()

  /**
   * The actor for creating playlist instances. This actor is global to the
   * factory and shared between multiple audio player instances.
   */
  private val playlistCreationActor = actorFactory.createPlaylistCreationActor()

  /**
   * The actor for extracting audio data. This actor is shared between all
   * audio player instances.
   */
  private val audioDataExtractorActor =
    actorFactory.createAudioSourceDataExtractorActor(createAudioSourceExtractor())

  /**
   * The actor for managing playback context factories and context creation.
   * This actor is shared between all audio player instances.
   */
  private val playbackCtxActor = actorFactory.createPlaybackContextActor()

  /** The size of the temporary buffer used by the streaming actor. */
  @volatile private var bufferSize = AudioPlayerFactoryImpl.DefaultBufferSize

  /** The set with the file extensions to be supported by the audio player. */
  @volatile private var fileExtensions =
    AudioPlayerFactoryImpl.DefaultFileExtensions

  /** The logger. */
  private val log = LoggerFactory.getLogger(getClass)

  /**
   * Creates a new instance of ''AudioPlayerFactoryImpl'' with a default
   * instance of a ''PlaylistCreationActor'' and a default actor factory.
   */
  def this() {
    this(AudioPlayerFactoryImpl.DefaultActorFactory)
  }

  /**
   * Returns the local buffer size used by this factory. Audio player instances
   * created by this factory will reserve a buffer of this size on the local
   * hard disk.
   * @return the local buffer size
   */
  def getBufferSize = bufferSize

  /**
   * Returns a set with the file extensions of media files supported by audio
   * player instances.
   * @return a set with file extensions of supported media files
   */
  def getFileExtensions = fileExtensions

  /**
   * Creates a new ''AudioPlayer'' instance.
   * @return the ''AudioPlayer'' instance
   */
  def createAudioPlayer(): AudioPlayer = {
    val gateway = new Gateway
    val tempFileFactory = new TempFileFactoryImpl
    val bufferManager = new SourceBufferManagerImpl(gateway)
    val ctxFactory = new PlaybackContextFactoryImpl
    val streamFactory = new SourceStreamWrapperFactoryImpl(bufferManager,
      tempFileFactory)

    val readActor = actorFactory.createSourceReaderActor(gateway, fsService,
      tempFileFactory, bufferSize / 2)
    val playbackActor = actorFactory.createPlaybackActor(gateway, ctxFactory,
      streamFactory)
    val lineActor = actorFactory.createLineActor(gateway)
    val timingActor = actorFactory.createTimingActor(gateway, new StopWatch)
    val eventActor = actorFactory.createEventTranslatorActor(gateway, 4)
    val playlistExtrActor =
      actorFactory.createPlaylistDataExtractorActor(gateway, audioDataExtractorActor)
    val plCtrlActor = actorFactory.createPlaylistCtrlActor(gateway, readActor,
      fsService, playlistFileStore, playlistCreationActor, Set("mp3"))

    gateway.start()
    readActor.start()
    playbackActor.start()
    lineActor.start()
    timingActor.start()
    eventActor.start()
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

  /**
   * Activates this component and passes in configuration properties.
   * @param props the map with configuration properties
   */
  protected[engine] def activate(props: java.util.Map[String, Object]) {
    log.info("Activating AudioPlayerFactoryImpl")
    if (props containsKey AudioPlayerFactoryImpl.PropBufferSize) {
      bufferSize = AudioPlayerFactoryImpl.parseBufferSize(
        String.valueOf(props get AudioPlayerFactoryImpl.PropBufferSize))
    }

    if (props containsKey AudioPlayerFactoryImpl.PropFileExtensions) {
      fileExtensions = AudioPlayerFactoryImpl.parseFileExtensions(
        String.valueOf(props get AudioPlayerFactoryImpl.PropFileExtensions))
    }

    playlistCreationActor.start()
    audioDataExtractorActor.start()
    playbackCtxActor.start()
  }

  /**
   * Deactivates this component. This implementation does some cleanup.
   */
  protected[engine] def deactivate() {
    log.info("Deactivating AudioPlayerFactoryImpl")
    playlistCreationActor ! Exit
    audioDataExtractorActor ! Exit
    playbackCtxActor ! Exit
  }

  /**
   * Injects a ''FSService'' reference.
   * @param svc the service instance
   */
  protected[engine] def bindFSService(svc: FSService) {
    fsService bind svc
    log.info("Bound FSService")
  }

  /**
   * Removes the ''FSService'' reference from this component.
   * @param svc the service reference to be removed
   */
  protected[engine] def unbindFSService(svc: FSService) {
    fsService unbind svc
    log.info("Unbound FSService")
  }

  /**
   * Injects a ''PlaylistGenerator'' service reference. This method is called
   * by the OSGi container when a new service implementation becomes available.
   * The properties of the generator service are also provided. This
   * implementation delegates management of ''PlaylistGenerator'' services to a
   * [[de.oliver_heger.splaya.playlist.impl.PlaylistCreationActor]] instance.
   * @param generator the generator service implementation
   * @param props a map with properties
   */
  protected[engine] def bindPlaylistGenerator(generator: PlaylistGenerator,
    props: java.util.Map[String, String]) {
    val mode = props get PlaylistGenerator.PropertyMode
    val isDefault = java.lang.Boolean.valueOf(
      props get PlaylistGenerator.PropertyDefault).booleanValue()
    playlistCreationActor ! AddPlaylistGenerator(generator, mode, isDefault)
    log.info("Bound playlist generator for mode {}.", Array(mode))
  }

  /**
   * Notifies this object that the given ''PlaylistGenerator'' service is no
   * longer available.
   * @param generator the affected generator service
   * @param props a map with properties
   */
  protected[engine] def unbindPlaylistGenerator(generator: PlaylistGenerator,
    props: java.util.Map[String, String]) {
    val mode = props get PlaylistGenerator.PropertyMode
    playlistCreationActor ! RemovePlaylistGenerator(generator, mode)
    log.info("Unbound playlist generator for mode {}.", Array(mode))
  }

  /**
   * Injects a [[de.oliver_heger.splaya.MediaDataExtractor]] service reference.
   * This method is called by the OSGi container when a corresponding service
   * implementation becomes available. This method passes the service reference
   * to the audio data extractor actor.
   * @param extr the extractor service implementation
   */
  protected[engine] def bindMediaDataExtractor(extr: MediaDataExtractor) {
    audioDataExtractorActor ! AddMediaDataExtractor(extr)
  }

  /**
   * Notifies this object that the given ''MediaDataExtractor'' service is no
   * longer available.
   * @param extr the affected extractor service
   */
  protected[engine] def unbindMediaDataExtractor(extr: MediaDataExtractor) {
    audioDataExtractorActor ! RemoveMediaDataExtractor(extr)
  }

  /**
   * Injects a [[de.oliver_heger.splaya.PlaybackContextFactory]] service
   * reference. This method is called by the OSGi container when a
   * corresponding service implementation becomes available. This method passes
   * the service reference to the actor which manages playback context
   * creation.
   * @param factory the playback context factory service
   */
  protected[engine] def bindPlaybackContextFactory(factory: PlaybackContextFactory) {
    playbackCtxActor ! AddPlaybackContextFactory(factory)
  }

  /**
   * Notifies this object that the given ''PlaybackContextFactory'' service is
   * no longer available.
   * @param factory the affected factory service
   */
  protected[engine] def unbindPlaybackContextFactory(factory: PlaybackContextFactory) {
    playbackCtxActor ! RemovePlaybackContextFactory(factory)
  }

  /**
   * Creates the ''AudioSourceDataExtractor'' instance used by the data
   * extractor actor.
   * @return the ''AudioSourceDataExtractor''
   */
  private def createAudioSourceExtractor() =
    new AudioSourceDataExtractorImpl(fsService)
}

/**
 * The companion object to ''AudioPlayerFactoryImpl''.
 */
object AudioPlayerFactoryImpl {
  /** Constant for the buffer size property. */
  val PropBufferSize = "audioPlayer.bufferSize"

  /** Constant for the file extensions property. */
  val PropFileExtensions = "audioPlayer.fileExtensions"

  /** The default buffer size. */
  val DefaultBufferSize = 10 * 1024 * 1024

  /** A set with default file extensions. */
  val DefaultFileExtensions = Set("mp3")

  /** A default actor factory. */
  val DefaultActorFactory = new ActorFactory {}

  /** Constant for the regular expression for splitting file extensions. */
  private val RegExSplitExtensions = "\\s*[,;]\\s*";

  /** A regular expression for parsing the buffer size property. */
  private val RegExBufferSize = """\s*(\d+)\s*([BbKkMm])?\s*""".r

  /** Constant for the system property with the user's home directory. */
  private val PropHomeDir = "user.home"

  /**
   * Constant for the sub directory in the user's home directory where to store
   * playlist-related data.
   */
  private val PlayaSubDir = "/.jplaya"

  /**
   * Determines the size of the local file buffer. This method tries to parse
   * the string value of the buffer size property. If it contains a unit, the
   * correct value in bytes is calculated. If the value cannot be parsed, an
   * exception is thrown.
   * @param value the string value of the property
   * @return the buffer size in bytes
   * @throws IllegalArgumentException if the value cannot be parsed
   */
  private def parseBufferSize(value: String): Int = {
    value match {
      case RegExBufferSize(numValue, unit) =>
        val size = numValue.toInt
        size * (if (unit == null) 1 else fetchSizeFactor(unit))

      case err =>
        throw new IllegalArgumentException("Not a valid buffer size: " + err)
    }
  }

  /**
   * Determines a factor for calculating the buffer size in bytes.
   * @param the unit provided to the property value
   * @return the factor corresponding to this unit
   */
  private def fetchSizeFactor(unit: String): Int =
    toLower(unit) match {
      case "b" => 1
      case "k" => 1024
      case "m" => 1024 * 1024
    }

  /**
   * Determines the file extensions supported by the audio player. This method
   * expects a comma-separated string as input. It is split, and the single
   * file extensions are returned as a set.
   * @param value the value of the file extensions property
   * @return a set with the extracted file extensions
   */
  private def parseFileExtensions(value: String): Set[String] =
    value.split(RegExSplitExtensions).map(toLower(_)).toSet

  /**
   * Creates the ''PlaylistFileStore'' object used by this factory. This object
   * is used by audio player instances to access media information. This
   * implementation creates an object with expects media data in the current
   * user's home directory.
   * @return the ''PlaylistFileStore'' object
   */
  private def createPlaylistFileStore() =
    new PlaylistFileStoreImpl((System getProperty PropHomeDir) + PlayaSubDir)

  /**
   * Helper method for converting a string to lower case.
   * @param s the string to be converted
   * @return the converted string
   */
  private def toLower(s: String): String = s.toLowerCase(Locale.ENGLISH)
}
