package de.oliver_heger.splaya.engine;
import java.util.Locale
import org.apache.commons.lang3.time.StopWatch
import de.oliver_heger.splaya.engine.io.SourceBufferManagerImpl
import de.oliver_heger.splaya.engine.io.SourceStreamWrapperFactoryImpl
import de.oliver_heger.splaya.engine.io.TempFileFactoryImpl
import de.oliver_heger.splaya.engine.msg.EventTranslatorActor
import de.oliver_heger.splaya.engine.msg.Gateway
import de.oliver_heger.splaya.fs.FSService
import de.oliver_heger.splaya.osgiutil.ServiceWrapper
import de.oliver_heger.splaya.playlist.impl.AudioSourceDataExtractorActor
import de.oliver_heger.splaya.playlist.impl.AudioSourceDataExtractorImpl
import de.oliver_heger.splaya.playlist.impl.PlaylistControllerImpl
import de.oliver_heger.splaya.playlist.impl.PlaylistCtrlActor
import de.oliver_heger.splaya.playlist.impl.PlaylistDataExtractorActor
import de.oliver_heger.splaya.playlist.impl.PlaylistFileStoreImpl
import de.oliver_heger.splaya.playlist.PlaylistFileStore
import de.oliver_heger.splaya.playlist.PlaylistGenerator
import de.oliver_heger.splaya.AudioPlayer
import de.oliver_heger.splaya.AudioPlayerFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * A factory class for constructing an [[de.oliver_heger.splaya.AudioPlayer]]
 * object.
 *
 * This class is a declarative services component which provides an audio
 * player creation service. It consumes some services required by audio player
 * instances:
 * $ - [[de.oliver_heger.splaya.fs.FSServive]] for accessing the file system
 * containing the media files to be played
 *
 * Some configuration properties can be set in the declarative services
 * configuration:
 * $ - `audioPlayer.bufferSize`: the size of the playback buffer to reserve on
 * the local hard disk; this is a string with an optional unit; units can be
 * B or b for bytes, K or k for kilobytes, M or m for megabytes; if the unit is
 * missing, it is assumed that the size is provided in bytes
 * $ - `audioPlayer.fileExtensions`: a comma-separated list with file extensions
 * of media files to be taken into account
 */
class AudioPlayerFactoryImpl extends AudioPlayerFactory {
  /** The object for managing the file system service. */
  protected[engine] val fsService = new ServiceWrapper[FSService]

  /** The ''PlaylistFileStore'' implementation used by this factory. */
  protected[engine] val playlistFileStore: PlaylistFileStore =
    AudioPlayerFactoryImpl.createPlaylistFileStore()

  /** The size of the temporary buffer used by the streaming actor. */
  @volatile private var bufferSize = AudioPlayerFactoryImpl.DefaultBufferSize

  /** The set with the file extensions to be supported by the audio player. */
  @volatile private var fileExtensions =
    AudioPlayerFactoryImpl.DefaultFileExtensions

  /** The logger. */
  private val log = LoggerFactory.getLogger(getClass)

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

  /** Constant for the regular expression for splitting file extensions. */
  private val RegExSplitExtensions = "\\s*[,;]\\s*";

  /** A regular expression for parsing the buffer size property. */
  private val RegExBufferSize = """\s*(\d+)\s*([BbKkMm])?\s*""".r

  /** Constant for the system property with the user's home directory. */
  private val PropHomeDir = "user.home"

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
    new PlaylistFileStoreImpl(System getProperty PropHomeDir)

  /**
   * Helper method for converting a string to lower case.
   * @param s the string to be converted
   * @return the converted string
   */
  private def toLower(s: String): String = s.toLowerCase(Locale.ENGLISH)
}
