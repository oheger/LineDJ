package de.oliver_heger.splaya.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar
import de.oliver_heger.splaya.fs.FSService
import de.oliver_heger.splaya.playlist.impl.AddPlaylistGenerator
import de.oliver_heger.splaya.playlist.impl.PlaylistCreationActor
import de.oliver_heger.splaya.playlist.impl.PlaylistFileStoreImpl
import de.oliver_heger.splaya.playlist.impl.RemovePlaylistGenerator
import de.oliver_heger.splaya.playlist.PlaylistGenerator
import de.oliver_heger.tsthlp.ActorTestImpl
import de.oliver_heger.tsthlp.QueuingActor
import de.oliver_heger.tsthlp.QueuingActor
import de.oliver_heger.splaya.engine.msg.Exit

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
   * Tests whether the activate() method starts the actor for playlist creation.
   */
  @Test def testActivateActorStarted() {
    val actor = new QueuingActor
    val factory = new AudioPlayerFactoryImpl(actor,
      AudioPlayerFactoryImpl.DefaultActorFactory)
    val props = new java.util.HashMap[String, Object]
    factory.activate(props)
    val msg = "Hello Actor"
    actor ! msg
    assertEquals("Message not received", msg, actor.nextMessage())
    actor.shutdown()
  }

  /**
   * Tests the cleanup performed by deactivate().
   */
  @Test def testDeactivate() {
    val actor = new QueuingActor
    actor.start()
    val factory = new AudioPlayerFactoryImpl(actor,
      AudioPlayerFactoryImpl.DefaultActorFactory)
    factory.deactivate()
    assertEquals("No Exit message", Exit, actor.nextMessage())
    actor.shutdown()
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
   * Tests whether a default playlist creation actor is created.
   */
  @Test def testDefaultPlaylistCreationActor() {
    val factory = new AudioPlayerFactoryImpl
    val playlistCreationActor =
      factory.playlistCreationActor.asInstanceOf[PlaylistCreationActor]
    assertNotNull("No playlist creation actor", playlistCreationActor)
  }

  /**
   * Tests whether a playlist generator service can be bound.
   */
  @Test def testBindPlaylistGenerator() {
    val generator = mock[PlaylistGenerator]
    val playlistCreatorActor = new ActorTestImpl
    val factory = new AudioPlayerFactoryImpl(playlistCreatorActor,
      AudioPlayerFactoryImpl.DefaultActorFactory)
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
    val factory = new AudioPlayerFactoryImpl(playlistCreatorActor,
      AudioPlayerFactoryImpl.DefaultActorFactory)
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
    val factory = new AudioPlayerFactoryImpl(playlistCreatorActor,
      AudioPlayerFactoryImpl.DefaultActorFactory)
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
}
