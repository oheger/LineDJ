package de.oliver_heger.splaya.engine

import java.io.InputStream

import org.easymock.EasyMock
import org.junit.Before
import org.junit.Test
import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar

import de.oliver_heger.splaya.AudioSource
import de.oliver_heger.splaya.{PlaybackContext => Context}
import de.oliver_heger.splaya.{PlaybackContextFactory => FactoryService}
import de.oliver_heger.tsthlp.QueuingActor
import de.oliver_heger.tsthlp.TestActorSupport

/**
 * Test class for ''PlaybackContextActor''.
 */
class TestPlaybackContextActor extends JUnitSuite with EasyMockSugar
  with TestActorSupport {
  /** The concrete actor type to be tested. */
  type ActorUnderTest = PlaybackContextActor

  /** A mock for a factory service. */
  private var factory1: FactoryService = _

  /** A mock for another factory service. */
  private var factory2: FactoryService = _

  /** The actor to be tested. */
  protected var actor: ActorUnderTest = _

  @Before def setUp() {
    actor = new ActorUnderTest
    actor.start()
    factory1 = mock[FactoryService]
    factory2 = mock[FactoryService]
    actor ! AddPlaybackContextFactory(factory2)
    actor ! AddPlaybackContextFactory(factory1)
  }

  /**
   * Creates a test audio source.
   * @return the test audio source
   */
  private def createSource(): AudioSource =
    AudioSource(uri = "SomeTestMusic.mp3", index = 42, length = 20121024221452L,
      skip = 0, skipTime = 0)

  /**
   * Creates a test actor acting as a sender for a create context request.
   * @return the test actor
   */
  private def createSender(): QueuingActor = {
    val sender = new QueuingActor
    sender.start()
    sender
  }

  /**
   * Tests a successful creation of a playback context.
   */
  @Test def testCreateRequestSuccess() {
    val stream = mock[InputStream]
    val source = createSource()
    val ctx = mock[Context]
    EasyMock.expect(factory1.createPlaybackContext(stream, source)).andReturn(Some(ctx))
    val sender = createSender()
    whenExecuting(stream, ctx, factory1, factory2) {
      actor ! CreatePlaybackContextRequest(stream, source, sender)
      sender.expectMessage(CreatePlaybackContextResponse(source, Some(ctx)))
    }
    sender.shutdown()
  }

  /**
   * Tests a create playback context request if one of the factory services
   * throws an exception. This aborts the process.
   */
  @Test def testCreateRequestEx() {
    val stream = mock[InputStream]
    val source = createSource()
    EasyMock.expect(factory1.createPlaybackContext(stream, source))
      .andThrow(new RuntimeException("TestException"))
    val sender = createSender()
    whenExecuting(stream, factory1, factory2) {
      actor ! CreatePlaybackContextRequest(stream, source, sender)
      sender.expectMessage(CreatePlaybackContextResponse(source, None))
    }
    sender.shutdown()
  }

  /**
   * Tests a request for creating a context if the audio source is not supported
   * by any of the factory services.
   */
  @Test def testCreateRequestUnsupported() {
    val stream = mock[InputStream]
    val source = createSource()
    EasyMock.expect(factory1.createPlaybackContext(stream, source))
      .andReturn(None)
    EasyMock.expect(factory2.createPlaybackContext(stream, source))
      .andReturn(None)
    val sender = createSender()
    whenExecuting(stream, factory1, factory2) {
      actor ! CreatePlaybackContextRequest(stream, source, sender)
      sender.expectMessage(CreatePlaybackContextResponse(source, None))
    }
    sender.shutdown()
  }

  /**
   * Tests whether a factory service can be removed.
   */
  @Test def testRemoveFactoryService() {
    val stream = mock[InputStream]
    val source = createSource()
    EasyMock.expect(factory1.createPlaybackContext(stream, source))
      .andReturn(None)
    val sender = createSender()
    whenExecuting(stream, factory1, factory2) {
      actor ! RemovePlaybackContextFactory(factory2)
      actor ! CreatePlaybackContextRequest(stream, source, sender)
      sender.expectMessage(CreatePlaybackContextResponse(source, None))
    }
    sender.shutdown()
  }
}
