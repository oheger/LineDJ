package de.oliver_heger.splaya.engine

import java.io.Closeable
import java.io.InputStream

import scala.actors.Actor
import scala.collection.mutable.Queue

import org.junit.Assert.assertEquals

import de.oliver_heger.splaya.AudioSource
import de.oliver_heger.splaya.PlaybackContext

/**
 * A mock actor simulating playback context creation used by the tests for the
 * playback actor.
 */
class MockPlaybackContextActor extends Actor {
  /** The queue with the expectations. */
  private val expectedRequests = Queue.empty[ExpectContextCreation]

  def act() {
    react {
      case cl: Closeable =>
        if (!expectedRequests.isEmpty) {
          throw new IllegalStateException("Not all expected requests processed!")
        }
        cl.close()

      case exp: ExpectContextCreation =>
        expectedRequests += exp
        act()

      case req: CreatePlaybackContextRequest =>
        val exp = expectedRequests.dequeue()
        assertEquals("Wrong source", exp.src, req.source)
        assertEquals("Wrong stream", exp.stream, req.stream)
        val msgs = CreatePlaybackContextResponse(req.source, exp.optCtx) :: exp.messages
        msgs foreach (req.sender ! _)
        exp.trigger.foreach(_.close())
        act()
    }
  }
}

/**
 * A message which adds an expectation to this actor.
 *
 * @param src the expected audio source
 * @param stream the expected audio stream
 * @param optCtx the context option to be sent to the receiver
 * @param messages an optional list with additional messages to be sent to the
 * receiver
 * @param trigger an optional trigger object to be fired after the processing
 * of this message
 */
case class ExpectContextCreation(src: AudioSource, stream: InputStream,
  optCtx: Option[PlaybackContext], messages: List[Any] = List(),
  trigger: Option[Closeable] = None)
