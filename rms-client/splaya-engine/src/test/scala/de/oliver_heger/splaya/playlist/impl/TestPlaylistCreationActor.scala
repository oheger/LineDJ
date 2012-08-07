package de.oliver_heger.splaya.playlist.impl

import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar
import org.junit.Test
import de.oliver_heger.tsthlp.TestActorSupport
import org.junit.Before
import de.oliver_heger.tsthlp.QueuingActor
import de.oliver_heger.tsthlp.QueuingActor
import de.oliver_heger.tsthlp.QueuingActor
import de.oliver_heger.splaya.playlist.PlaylistGenerator
import org.easymock.EasyMock
import de.oliver_heger.tsthlp.QueuingActor
import de.oliver_heger.splaya.PlaylistSettings

/**
 * Test class for ''PlaylistCreationActor''.
 */
class TestPlaylistCreationActor extends JUnitSuite with EasyMockSugar
  with TestActorSupport {
  /** The actor type to be tested. */
  type ActorUnderTest = PlaylistCreationActor

  /** The actor to be tested. */
  protected var actor: ActorUnderTest = _

  @Before def startUp() {
    actor = new PlaylistCreationActor
    actor.start()
  }

  /**
   * Tests whether the actor processes an exit message.
   */
  @Test def testExit() {
    shutdownActor()
  }

  /**
   * Sends a request for a new playlist to the test actor. A queuing actor is
   * created as receiver of the response and returned.
   */
  private def requestPlaylist(): QueuingActor = {
    val sender = new QueuingActor
    sender.start()
    val msg = GeneratePlaylist(songs = TestPlaylistCreationActor.OriginalPlaylist,
      sender = sender, settings = TestPlaylistCreationActor.PlaylistSettings)
    actor ! msg
    sender
  }

  /**
   * Extracts a playlist generated response from the specified actor. If the
   * next message is not of this type, the test case fails.
   * @param q the sending actor
   * @return the extracted response message
   */
  private def extractResponse(q: QueuingActor): PlaylistGenerated =
    q.nextMessage() match {
      case gen: PlaylistGenerated => gen
      case other =>
        fail("Unexpected message: " + other)
    }

  /**
   * Helper method for testing whether a playlist generated response contains
   * the expected songs. The response is extracted from the actor. Based on
   * the boolean parameter it is checked whether it has been processed by a
   * generator or not.
   * @param q the sending actor
   * @param processed a flag whether a processed playlist ('''true''') or the
   * original playlist ('''false''') is expected
   */
  private def checkResponse(q: QueuingActor, processed: Boolean) {
    val resp = extractResponse(q)
    val expectedSongs = if (processed) TestPlaylistCreationActor.ProcessedPlaylist
    else TestPlaylistCreationActor.OriginalPlaylist
    assert(expectedSongs === resp.songs)
    assert(TestPlaylistCreationActor.PlaylistSettings === resp.settings)
    q.shutdown()
  }

  /**
   * Prepares the specified mock to expect a generatePlaylist() invocation.
   * @param gen the generator mock
   */
  private def expectGeneratePlaylist(gen: PlaylistGenerator) {
    EasyMock.expect(gen.generatePlaylist(TestPlaylistCreationActor.OriginalPlaylist,
      TestPlaylistCreationActor.Mode, TestPlaylistCreationActor.Params))
      .andReturn(TestPlaylistCreationActor.ProcessedPlaylist)
  }

  /**
   * Tests whether a playlist is created if there are no generators.
   */
  @Test def testCreatePlaylistNoGenerators() {
    val client = requestPlaylist()
    checkResponse(client, false)
  }

  /**
   * Tests whether a playlist creation request is delegated to the correct
   * generator.
   */
  @Test def testCreatePlaylistWithGenerator() {
    val gen1 = mock[PlaylistGenerator]
    val gen2 = mock[PlaylistGenerator]
    expectGeneratePlaylist(gen1)
    whenExecuting(gen1, gen2) {
      actor ! AddPlaylistGenerator(gen1, TestPlaylistCreationActor.Mode, false)
      actor ! AddPlaylistGenerator(gen2, "otherMode", false)
      val client = requestPlaylist()
      checkResponse(client, true)
    }
  }

  /**
   * Tests whether a default playlist generator is used if not matching one is
   * found.
   */
  @Test def testDefaultGenerator() {
    val gen1 = mock[PlaylistGenerator]
    val gen2 = mock[PlaylistGenerator]
    val gen3 = mock[PlaylistGenerator]
    expectGeneratePlaylist(gen2)
    whenExecuting(gen1, gen2, gen3) {
      actor ! AddPlaylistGenerator(gen1, "someMode", true)
      actor ! AddPlaylistGenerator(gen2, "someOtherMode", true)
      actor ! AddPlaylistGenerator(gen3, "andAnotherMode", false)
      val client = requestPlaylist()
      checkResponse(client, true)
    }
  }

  /**
   * Tests whether playlist generators can be removed.
   */
  @Test def testRemoveGenerator() {
    val gen1 = mock[PlaylistGenerator]
    val gen2 = mock[PlaylistGenerator]
    whenExecuting(gen1, gen2) {
      actor ! AddPlaylistGenerator(gen1, TestPlaylistCreationActor.Mode, true)
      actor ! AddPlaylistGenerator(gen2, "otherMode", false)
      actor ! RemovePlaylistGenerator(gen1, TestPlaylistCreationActor.Mode)
      val client = requestPlaylist()
      checkResponse(client, false)
    }
  }
}

object TestPlaylistCreationActor {
  /** A test playlist. */
  val OriginalPlaylist = List("Learning to fly", "More", "Tubular Bells")

  /** A processed playlist. */
  val ProcessedPlaylist = OriginalPlaylist.reverse

  /** Constant for a mode string. */
  val Mode = "Directories"

  /** Constant for a node sequence for additional parameters. */
  val Params = <params>test</params>

  /** Constant for the default settings object. */
  val PlaylistSettings = createSettings()

  /**
   * Generates a data object with default playlist settings.
   * @return the settings object
   */
  private def createSettings(): PlaylistSettings = {
    val settings = EasyMock.createMock(classOf[PlaylistSettings])
    EasyMock.expect(settings.orderMode).andReturn(Mode).anyTimes()
    EasyMock.expect(settings.orderParams).andReturn(Params).anyTimes()
    EasyMock.replay(settings)
    settings
  }
}
