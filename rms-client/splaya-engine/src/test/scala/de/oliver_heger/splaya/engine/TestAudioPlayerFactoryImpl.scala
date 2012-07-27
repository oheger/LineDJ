package de.oliver_heger.splaya.engine

import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar
import org.junit.Before
import org.junit.Test
import org.junit.Assert._
import de.oliver_heger.splaya.fs.FSService
import de.oliver_heger.splaya.playlist.impl.PlaylistFileStoreImpl

/**
 * Test class for ''AudioPlayerFactoryImpl''.
 */
class TestAudioPlayerFactoryImpl extends JUnitSuite with EasyMockSugar {
  /** The factory to be tested. */
  private var factory: AudioPlayerFactoryImpl = _

  @Before def setUp() {
    factory = new AudioPlayerFactoryImpl
  }

  /**
   * Tests the initial values of properties.
   */
  @Test def testDefaultProperties() {
    assertEquals("Wrong default buffer size",
      AudioPlayerFactoryImpl.DefaultBufferSize, factory.getBufferSize)
    assertEquals("Wrong default file extensions",
      AudioPlayerFactoryImpl.DefaultFileExtensions, factory.getFileExtensions)
  }

  /**
   * Tests activate() if no properties are passed.
   */
  @Test def testActivateNoProperties() {
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
    val props = new java.util.HashMap[String, Object]
    props.put(AudioPlayerFactoryImpl.PropFileExtensions, "Mp3 , wAv, au,ogg")
    factory.activate(props)
    assertEquals("Wrong file extensions", Set("mp3", "wav", "au", "ogg"),
      factory.getFileExtensions)
  }

  /**
   * Helper method for checking whether the buffer size can be parsed correctly.
   * @param value the property value
   * @param expected the expected buffer size
   */
  private def checkSetBufferSize(value: String, expected: Int) {
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
    val fsService = mock[FSService]
    factory bindFSService fsService
    assertSame("Service not bound", fsService, factory.fsService.get)
  }

  /**
   * Tests whether a file system service can be removed.
   */
  @Test def testUnbindFSService() {
    val fsService = mock[FSService]
    factory bindFSService fsService
    factory unbindFSService fsService
    assertNull("Still got a service", factory.fsService.get)
  }

  /**
   * Tests the playlist file store implementation.
   */
  @Test def testPlaylistFileStore() {
    val store = factory.playlistFileStore.asInstanceOf[PlaylistFileStoreImpl]
    assertEquals("Wrong directory", System.getProperty("user.home"),
      store.directoryName)
  }

  /**
   * Tests whether a new audio player instance can be created. Note: we cannot
   * test many properties of the newly created player instance.
   */
  @Test def testCreateAudioPlayer() {
    val player = factory.createAudioPlayer().asInstanceOf[AudioPlayerImpl]
    player.shutdown()
    player.gateway.shutdown()
  }
}
