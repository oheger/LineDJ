package de.oliver_heger.splaya.engine;

import scala.actors.Actor
import scala.collection.mutable.Queue

/**
 * <p>An actor for handling audio playback.</p>
 * <p>This actor receives messages when new audio data has been streamed to the
 * temporary buffer. Its task is to process the single audio streams and send
 * them chunk-wise to the <em>line write actor</em>.</p>
 */
class PlaybackActor(ctxFactory: PlaybackContextFactory,
  bufferManager: SourceBufferManager, tempFileFactory: TempFileFactory)
  extends Actor {
  /** Constant for the default buffer size. */
  private val BufferSize = 4096

  /** A queue with the audio sources to be played.*/
  private val queue = Queue.empty[AudioSource]

  /** The current context. */
  private var context: PlaybackContext = _

  /** The current input stream. */
  private var stream: SourceStreamWrapper = _

  /** The buffer for feeding the line. */
  private var playbackBuffer : Array[Byte] = _

  /**
   * The main message loop of this actor.
   */
  def act() {
    var running = true

    while(running) {
      receive {
        case Exit => running = false

        case src : AudioSource =>
          enqueue(src)

        case ChunkPlayed =>
          playback()
      }
    }
  }

  /**
   * Adds the specified audio source to the internal queue of songs to play.
   * If no song is currently played, playback starts.
   * @param src the audio source
   */
  private def enqueue(src : AudioSource) {
    queue += src
    if(context == null) {
      playback()
    }
  }

  /**
   * Initiates playback.
   */
  private def playback() {
    if(context != null || setUpPlaybackContext()) {
      playChunk()
    }
  }

  /**
   * Plays a chunk of the audio data.
   */
  private def playChunk() {
    val len = context.audioStream.read(playbackBuffer)
    if(len < 0) {
      context.close()
      context = null
      playback()
    } else {
      Gateway ! Gateway.ActorLineWrite -> PlayChunk(context.line,
          playbackBuffer, len)
    }
  }

  /**
   * Creates the objects required for the playback of a new audio file. If there
   * are no more audio files to play, this method returns <b>false</b>.
   * @return a flag if there are more files to play
   */
  private def setUpPlaybackContext(): Boolean = {
    if (queue.isEmpty) {
      println("Playback ends.")
      false
    } else {
      preparePlayback()
      true
    }
  }

  /**
   * Prepares the playback of a new audio file and creates the necessary context
   * objects.
   */
  private def preparePlayback() {
    val source = queue.dequeue()
    println("Playing " + source.name)

    val sourceStream = if (stream != null) stream.currentStream else null
    stream = new SourceStreamWrapper(tempFileFactory, sourceStream,
      source.length.toInt, bufferManager)
    context = ctxFactory.createPlaybackContext(stream)
    playbackBuffer = createPlaybackBuffer()
    prepareLine()
  }

  /**
   * Creates the byte array to be used as buffer for feeding the line.
   * @return the byte buffer
   */
  private def createPlaybackBuffer(): Array[Byte] = {
    var size = BufferSize

    if (size % context.format.getFrameSize != 0) {
      size = ((size / context.format.getFrameSize) + 1) *
        context.format.getFrameSize
    }

    new Array[Byte](size)
  }

  /**
   * Prepares the current line to start playback.
   */
  private def prepareLine() {
    context.line.open(context.format)
    context.line.start()
  }
}