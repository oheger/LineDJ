package de.oliver_heger.splaya.engine

import scala.actors.Actor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar

import de.oliver_heger.splaya.engine.msg.Exit
import de.oliver_heger.splaya.fs.FSService
import de.oliver_heger.splaya.playlist.impl.AudioSourceDataExtractorImpl
import de.oliver_heger.splaya.playlist.impl.AddMediaDataExtractor
import de.oliver_heger.splaya.playlist.impl.AddPlaylistGenerator
import de.oliver_heger.splaya.playlist.impl.AudioSourceDataExtractor
import de.oliver_heger.splaya.playlist.impl.PlaylistFileStoreImpl
import de.oliver_heger.splaya.playlist.impl.RemoveMediaDataExtractor
import de.oliver_heger.splaya.playlist.impl.RemovePlaylistGenerator
import de.oliver_heger.splaya.playlist.PlaylistGenerator
import de.oliver_heger.splaya.MediaDataExtractor
import de.oliver_heger.tsthlp.ActorTestImpl
import de.oliver_heger.tsthlp.QueuingActor

/**
 * Test class for ''AudioPlayerFactoryImpl''.
 */
class TestAudioPlayerFactoryImpl extends JUnitSuite with EasyMockSugar {
  /**
   * Tests the initial values of properties.
   */
  @Test def testDefaultProperties() {
    val factory = new AudioPlayerFactoryImpl
    assertEquals("Wrong default buffer size",
      AudioPlayerFactoryImpl.DefaultBufferSize, factory.getBufferSize)
    assertEquals("Wrong default file extensions",
      AudioPlayerFactoryImpl.DefaultFileExtensions, factory.getFileExtensions)
  }

  /**
   * Tests activate() if no properties are passed.
   */
  @Test def testActivateNoProperties() {
    val factory = new AudioPlayerFactoryImpl
    val props = new java.util.HashMap[String, Object]
    factory.activate(props)
    assertEquals("Wrong buffer size",
      AudioPlayerFactoryImpl.DefaultBufferSize, factory.getBufferSize)
    assertEquals("Wrong file extensions",
      AudioPlayerFactoryImpl.DefaultFileExtensions, factory.getFileExtensions)
  }

  /**
   * Tests whether the property with file extensions is evaluated.
   */
  @Test def testActivateFileExtensionsProperty() {
    val factory = new AudioPlayerFactoryImpl
    val props = new java.util.HashMap[String, Object]
    props.put(AudioPlayerFactoryImpl.PropFileExtensions, "Mp3 , wAv, au,ogg")
    factory.activate(props)
    assertEquals("Wrong file extensions", Set("mp3", "wav", "au", "ogg"),
      factory.getFileExtensions)
  }

  /**
   * Helper method for testing whether activate() starts the global actors.
   * @param actFactory the test actor factory
   * @param actor the test actor
   */
  private def checkActivateActorStarted(actFactory: ActorFactory, actor: QueuingActor) {
    val factory = new AudioPlayerFactoryImpl(actFactory)
    val props = new java.util.HashMap[String, Object]
    factory.activate(props)
    val msg = "Hello Actor"
    actor ! msg
    assertEquals("Message not received", msg, actor.nextMessage())
    factory.deactivate()
    actor.shutdown()
  }

  /**
   * Tests whether the activate() method starts the actor for playlist creation.
   */
  @Test def testActivatePLActorStarted() {
    val actor = new QueuingActor
    val actFactory = new MockPLCreationActorFactory(actor)
    checkActivateActorStarted(actFactory, actor)
  }

  /**
   * Tests whether the activate() method starts the actor for extracting audio
   * data.
   */
  @Test def testActivateExtrActorStarted() {
    val actor = new QueuingActor
    val actFactory = new MockExtractorActorFactory(actor)
    checkActivateActorStarted(actFactory, actor)
  }

  /**
   * Tests whether the deactivate() callback shuts down global actors.
   */
  private def checkDeactivateActorExit(actFactory: ActorFactory, actor: QueuingActor) {
    actor.start()
    val factory = new AudioPlayerFactoryImpl(actFactory)
    factory.deactivate()
    assertEquals("No Exit message", Exit, actor.nextMessage())
    actor.shutdown()
  }

  /**
   * Tests whether deactivate() shuts down the playlist creation actor.
   */
  @Test def testDeactivatePLActorExit() {
    val actor = new QueuingActor
    checkDeactivateActorExit(new MockPLCreationActorFactory(actor), actor)
  }

  /**
   * Tests whether deactivate() shuts down the data extractor actor.
   */
  @Test def testDeactivateExtrActorExit() {
    val actor = new QueuingActor
    checkDeactivateActorExit(new MockExtractorActorFactory(actor), actor)
  }

  /**
   * Helper method for checking whether the buffer size can be parsed correctly.
   * @param value the property value
   * @param expected the expected buffer size
   */
  private def checkSetBufferSize(value: String, expected: Int) {
    val factory = new AudioPlayerFactoryImpl
    val props = new java.util.HashMap[String, Object]
    props.put(AudioPlayerFactoryImpl.PropBufferSize, value)
    factory activate props
    assertEquals("Wrong buffer size", expected, factory.getBufferSize)
  }

  /**
   * Tests whether the buffer size can be parsed if no unit is provided.
   */
  @Test def testActivateBufferSizeNoUnit() {
    checkSetBufferSize("1000", 1000)
  }

  /**
   * Tests whether the buffer size can be specified in bytes.
   */
  @Test def testActivateBufferSizeBytes() {
    checkSetBufferSize("1024b", 1024)
  }

  /**
   * Tests whether the buffer size can be specified in KB.
   */
  @Test def testActivateBufferSizeKBytes() {
    checkSetBufferSize("1 K", 1024)
  }

  /**
   * Tests whether the buffer size can be specified in MB.
   */
  @Test def testActivateBufferSizeMBytes() {
    checkSetBufferSize("  1    M  ", 1024 * 1024)
  }

  /**
   * Tests whether an unknown unit is detected when defining the buffer size.
   */
  @Test(expected = classOf[IllegalArgumentException])
  def testActivateBufferSizeUnknownUnit() {
    checkSetBufferSize("10 X", 0)
  }

  /**
   * Tries to specify the buffer size in an invalid format.
   */
  @Test(expected = classOf[IllegalArgumentException])
  def testActivateBufferSizeInvalidFormat() {
    checkSetBufferSize("-1", 0)
  }

  /**
   * Tests whether a file system service can be bound.
   */
  @Test def testBindFSService() {
    val factory = new AudioPlayerFactoryImpl
    val fsService = mock[FSService]
    factory bindFSService fsService
    assertSame("Service not bound", fsService, factory.fsService.get)
  }

  /**
   * Tests whether a file system service can be removed.
   */
  @Test def testUnbindFSService() {
    val factory = new AudioPlayerFactoryImpl
    val fsService = mock[FSService]
    factory bindFSService fsService
    factory unbindFSService fsService
    assertNull("Still got a service", factory.fsService.get)
  }

  /**
   * Tests the playlist file store implementation.
   */
  @Test def testPlaylistFileStore() {
    val factory = new AudioPlayerFactoryImpl
    val store = factory.playlistFileStore.asInstanceOf[PlaylistFileStoreImpl]
    assertEquals("Wrong directory", System.getProperty("user.home") + "/.jplaya",
      store.directoryName)
  }

  /**
   * Tests whether a new audio player instance can be created. Note: we cannot
   * test many properties of the newly created player instance.
   */
  @Test def testCreateAudioPlayer() {
    val factory = new AudioPlayerFactoryImpl
    val player = factory.createAudioPlayer().asInstanceOf[AudioPlayerImpl]
    player.shutdown()
    player.gateway.shutdown()
  }

  /**
   * Tests whether a playlist generator service can be bound.
   */
  @Test def testBindPlaylistGenerator() {
    val generator = mock[PlaylistGenerator]
    val playlistCreatorActor = new ActorTestImpl
    val actorFactory = new MockPLCreationActorFactory(playlistCreatorActor)
    val factory = new AudioPlayerFactoryImpl(actorFactory)
    val props = new java.util.HashMap[String, String]
    props.put(PlaylistGenerator.PropertyMode, "directories")
    factory.bindPlaylistGenerator(generator, props)
    playlistCreatorActor.nextMessage() match {
      case addgen: AddPlaylistGenerator =>
        assertSame("Wrong generator", generator, addgen.generator)
        assertSame("Wrong mode", "directories", addgen.mode)
        assertFalse("A default generator", addgen.useAsDefault)

      case other =>
        fail("Unexpected message: " + other)
    }
    playlistCreatorActor.ensureNoMessages()
  }

  /**
   * Tests whether the default generator property is evaluated when binding a
   * playlist generator.
   */
  @Test def testBindPlaylistGeneratorDefault() {
    val generator = mock[PlaylistGenerator]
    val playlistCreatorActor = new ActorTestImpl
    val actorFactory = new MockPLCreationActorFactory(playlistCreatorActor)
    val factory = new AudioPlayerFactoryImpl(actorFactory)
    val props = new java.util.HashMap[String, String]
    props.put(PlaylistGenerator.PropertyMode, "directories")
    props.put(PlaylistGenerator.PropertyDefault, "True")
    factory.bindPlaylistGenerator(generator, props)
    playlistCreatorActor.nextMessage() match {
      case addgen: AddPlaylistGenerator =>
        assertSame("Wrong generator", generator, addgen.generator)
        assertTrue("Not a default generator", addgen.useAsDefault)

      case other =>
        fail("Unexpected message: " + other)
    }
    playlistCreatorActor.ensureNoMessages()
  }

  /**
   * Tests whether a playlist generator service can be removed.
   */
  @Test def testUnbindPlaylistGenerator() {
    val generator = mock[PlaylistGenerator]
    val playlistCreatorActor = new ActorTestImpl
    val actorFactory = new MockPLCreationActorFactory(playlistCreatorActor)
    val factory = new AudioPlayerFactoryImpl(actorFactory)
    val props = new java.util.HashMap[String, String]
    props.put(PlaylistGenerator.PropertyMode, "directories")
    factory.unbindPlaylistGenerator(generator, props)
    playlistCreatorActor.nextMessage() match {
      case removegen: RemovePlaylistGenerator =>
        assertSame("Wrong generator", generator, removegen.generator)
        assertEquals("Wrong mode", "directories", removegen.mode)

      case other =>
        fail("Unexpected message: " + other)
    }
    playlistCreatorActor.ensureNoMessages()
  }

  /**
   * Tests whether a default actor factory is set.
   */
  @Test def testDefaultActorFactory() {
    val factory = new AudioPlayerFactoryImpl
    assertSame("Wrong default factory",
      AudioPlayerFactoryImpl.DefaultActorFactory, factory.actorFactory)
  }

  /**
   * Tests whether a media data extractor service can be attached to the
   * factory.
   */
  @Test def testBindMediaDataExtractor() {
    val extr = mock[MediaDataExtractor]
    val extrActor = new ActorTestImpl
    val factory = new AudioPlayerFactoryImpl(new MockExtractorActorFactory(extrActor))
    factory bindMediaDataExtractor extr
    extrActor.expectMessage(AddMediaDataExtractor(extr))
    extrActor.ensureNoMessages()
  }

  /**
   * Tests whether a media data extractor service can be removed from the
   * factory.
   */
  @Test def testUnbindMediaDataExtractor() {
    val extr = mock[MediaDataExtractor]
    val extrActor = new ActorTestImpl
    val factory = new AudioPlayerFactoryImpl(new MockExtractorActorFactory(extrActor))
    factory unbindMediaDataExtractor extr
    extrActor.expectMessage(RemoveMediaDataExtractor(extr))
    extrActor.ensureNoMessages()
  }

  /**
   * A specialized actor factory which allows mocking the playlist creation
   * actor.
   */
  private class MockPLCreationActorFactory(override val createPlaylistCreationActor: Actor)
    extends ActorFactory

  /**
   * A specialized actor factory which allows mocking the audio data extractor
   * actor.
   */
  private class MockExtractorActorFactory(extrActor: Actor) extends ActorFactory {
    override def createAudioSourceDataExtractorActor(
      extr: AudioSourceDataExtractor): Actor = {
      assertEquals("Wrong extractor", classOf[AudioSourceDataExtractorImpl],
        extr.getClass)
      extrActor
    }
  }
}
