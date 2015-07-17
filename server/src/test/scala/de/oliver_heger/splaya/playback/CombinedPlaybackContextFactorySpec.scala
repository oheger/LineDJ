package de.oliver_heger.splaya.playback

import java.io.InputStream

import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
 * Test class for ''CombinedPlaybackContextFactory''.
 */
class CombinedPlaybackContextFactorySpec extends FlatSpec with Matchers with MockitoSugar {
  /** A test URI. */
  private val Uri = "Some test URI to a test file"

  /**
   * Returns a mock for a sub factory. The mock is initialized to return None.
   * @return the mock sub factory
   */
  private def subFactory(): PlaybackContextFactory = {
    val subFactory = mock[PlaybackContextFactory]
    when(subFactory.createPlaybackContext(any(classOf[InputStream]), anyString())).thenReturn(None)
    subFactory
  }

  "A CombinedPlaybackContextFactory" should "return None if there are no sub factories" in {
    val factory = new CombinedPlaybackContextFactory(Nil)
    factory.createPlaybackContext(mock[InputStream], Uri) shouldBe 'empty
  }

  it should "invoke its sub factories" in {
    val sub1 = subFactory()
    val sub2 = subFactory()
    val stream = mock[InputStream]

    val factory = new CombinedPlaybackContextFactory(List(sub1, sub2))
    factory.createPlaybackContext(stream, Uri) shouldBe 'empty
    verify(sub1).createPlaybackContext(stream, Uri)
    verify(sub2).createPlaybackContext(stream, Uri)
  }

  it should "return the first valid playback context" in {
    val sub1 = subFactory()
    val sub2 = subFactory()
    val sub3 = subFactory()
    val stream = mock[InputStream]
    val context = mock[PlaybackContext]
    when(sub2.createPlaybackContext(stream, Uri)).thenReturn(Some(context))

    val factory = new CombinedPlaybackContextFactory(List(sub1, sub2, sub3))
    factory.createPlaybackContext(stream, Uri).get should be(context)
    verify(sub2).createPlaybackContext(stream, Uri)
    verifyZeroInteractions(sub3)
  }

  it should "support adding a new sub factory" in {
    val sub1 = subFactory()
    val sub2 = subFactory()
    val stream = mock[InputStream]
    val context = mock[PlaybackContext]
    when(sub2.createPlaybackContext(stream, Uri)).thenReturn(Some(context))

    val factory = new CombinedPlaybackContextFactory(List(sub1))
    val factory2 = factory addSubFactory sub2
    factory2.createPlaybackContext(stream, Uri).get should be(context)
    factory.createPlaybackContext(stream, Uri) shouldBe 'empty
  }

  it should "support removing a sub factory" in {
    val sub1 = subFactory()
    val sub2 = subFactory()
    val stream = mock[InputStream]

    val factory = new CombinedPlaybackContextFactory(List(sub1, sub2))
    val factory2 = factory removeSubFactory sub2
    factory2.createPlaybackContext(stream, Uri) shouldBe 'empty
    verifyZeroInteractions(sub2)
  }
}
