package de.oliver_heger.splaya.engine.msg
import java.io.Closeable

import scala.actors.Actor

import org.slf4j.LoggerFactory

import javax.sound.sampled.SourceDataLine

/**
 * A message that indicates that processing should be aborted.
 */
case object Exit extends Closeable {
  /** The logger. */
  private val log = LoggerFactory.getLogger("Exit")

  def close() {
    log.debug("Actor exits.")
  }
}

/**
 * A message for adding a stream to be played to the source reader actor.
 * Messages of this type are typically sent by the component which controls the
 * current playlist.
 * @param rootURI the URI of the root folder of the source medium
 * @param uri the (relative) URI of the audio stream to be played
 * @param index the index of this audio source in the current playlist
 * @param skip the initial skip position
 * @param skipTime the time to be skipped in the beginning of this audio stream
 */
case class AddSourceStream(rootURI: String, uri: String, index: Int, skip: Long,
  skipTime: Long) {
  /**
   * A specialized constructor for creating an instance that does not contain
   * any skip information. This means that the referenced audio stream is to
   * be played directly from start.
   * @param uri the URI of the audio stream to be played
   * @param index the index of this audio source in the current playlist
   */
  def this(rootURI: String, uri: String, index: Int) =
    this(rootURI, uri, index, 0, 0)

  /**
   * A specialized constructor for creating an instance that does not contain
   * any real data. Such an instance can be used to indicate the end of a
   * playlist.
   */
  def this() = this(null, null, -1)

  /**
   * A flag whether this instance contains actual data.
   */
  val isDefined = uri != null && index >= 0
}

/**
 * A message which instructs the reader actor to read another chunk copy it to
 * the target location.
 */
case object ReadChunk

/**
 * A message for writing a chunk of audio data into the specified line.
 * @param line the line
 * @param chunk the array with the data to be played
 * @param len the length of the array (the number of valid bytes)
 * @param currentPos the current position in the source stream
 * @param skipPos the skip position for the current stream
 */
case class PlayChunk(line: SourceDataLine, chunk: Array[Byte], len: Int,
  currentPos: Long, skipPos: Long)

/**
 * A message which indicates that a chunk of audio data was played. The chunk
 * may be written partly; the exact number of bytes written to the data line
 * is contained in the message.
 * @param bytesWritten the number of bytes which has been written
 */
case class ChunkPlayed(bytesWritten: Int)

/**
 * A message sent out by the source reader actor if reading from a source
 * causes an error. This message tells the playback actor that the current
 * source is skipped after this position. Playback can continue with the next
 * source.
 */
case class SourceReadError(bytesRead: Long)

/**
 * A message which tells the playback actor that playback should start now.
 */
case object StartPlayback

/**
 * A message which tells the playback actor that playback should pause.
 */
case object StopPlayback

/**
 * A message which tells the playback actor to skip the currently played audio
 * stream.
 */
case object SkipCurrentSource

/**
 * A simple data class defining a message to be sent to an actor. An instance
 * holds the receiving actor and the actual message.
 *
 * @param receiver the receiving actor
 * @param msg the content of the message
 */
case class MsgDef(receiver: Actor, msg: Any) {
  /**
   * Sends the message defined by this message definition to its receiver.
   */
  def send() {
    receiver ! msg
  }
}

/**
 * A message indicating that the audio player is to be flushed. Flushing means
 * that all actors wipe out their current state so that playback can start
 * anew, at a different position in the playlist.
 *
 * In some cases, actors first have to be flushed before a new action can
 * happen (e.g. reading the content of a source medium or moving to another
 * position in the playlist). To avoid race conditions, it has to be ensured
 * that these actions are not triggered before the flush operation is
 * complete. This class provides some support in this area by accepting a
 * list of messages. An actor handling a message of this type can then
 * send these messages to their corresponding receivers. That way other actors
 * can be triggered immediately after the flush.
 *
 * @param followMessages an optional list of messages to be sent after the
 * flush message has been processed
 */
case class FlushPlayer(followMessages: List[MsgDef] = Nil) {
  /**
   * Processes all follow messages defined for this object to the corresponding
   * receivers.
   */
  def sendFollowMessages() {
    followMessages foreach (_.send())
  }
}

/**
 * A message to be evaluated by [[de.oliver_heger.splaya.engine.TimingActor]]
 * for performing a specific action and passing in the current time. The timing
 * actor will call the function which is part of the message and passes the
 * current time as argument. That way the current playback time can be accessed
 * in a thread-safe fashion.
 * @param f the function to be invoked with the current playback time
 */
case class TimeAction(f: Long => Unit)

/**
 * A message sent by the ''SourceReaderActor'' whenever it copies data from the
 * source medium. Messages of this type can be used to avoid parallel accesses
 * to the source medium. The idea behind this concept is the following:
 *
 * Typically audio data is read from a CD ROM drive. If other components access
 * this data (e.g. for obtaining ID3 tags), the reads are slowed down. To
 * ensure that playback is not interrupted, the source reader actor must have
 * priority. Therefore, other components which also need access to the source
 * medium should observe this messages and interrupt their activities if the
 * source reader locks the medium (in this case the ''lock'' parameter is set
 * to '''true'''). After copying a chunk from the source medium, another message
 * is sent with the ''lock'' parameter set to '''false'''.
 * @param lock a flag whether the source medium is locked or unlocked
 */
case class AccessSourceMedium(lock: Boolean)

/**
 * A message indicating that an actor has exited. Messages of this type are
 * sent out as the very last action of actors of the audio engine. This
 * mechanism can be used to find out when all important actors have shut down
 * (then basically the whole audio player engine has completed its shutdown).
 * @param actor the actor which sends this message
 */
case class ActorExited(actor: Actor)
