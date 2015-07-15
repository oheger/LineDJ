package de.oliver_heger.splaya.mp3

import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar
import org.junit.Before
import org.junit.Test
import org.junit.Assert._
import org.easymock.EasyMock

/**
 * Test class for ''CombinedID3TagProvider''.
 */
class TestCombinedID3TagProvider extends JUnitSuite with EasyMockSugar {
  /** Constant for a test tag value. */
  private val Value = "TestTagValue"

  /** An array with mocks for sub providers. */
  private var subProviders: Array[ID3TagProvider] = _

  /** The combined provider to be tested. */
  private var provider: CombinedID3TagProvider = _

  @Before def setUp() {
    subProviders = new Array(3)
    for (i <- 0 until subProviders.length) {
      subProviders(i) = mock[ID3TagProvider]
    }
    provider = new CombinedID3TagProvider(subProviders.toList)
  }

  /**
   * Tests the case that none of the sub providers has a value.
   */
  @Test def testNoValue() {
    subProviders foreach { p => EasyMock.expect(p.title).andReturn(None) }
    whenExecuting(subProviders: _*) {
      assertFalse("Got a result", provider.title.isDefined)
    }
  }

  /**
   * Tests whether the title can be queried.
   */
  @Test def testTitle() {
    EasyMock.expect(subProviders(0).title).andReturn(None)
    EasyMock.expect(subProviders(1).title).andReturn(Some(Value))
    whenExecuting(subProviders: _*) {
      assertEquals("Wrong result", Value, provider.title.get)
    }
  }

  /**
   * Tests whether the artist can be queried.
   */
  @Test def testArtist() {
    EasyMock.expect(subProviders(0).artist).andReturn(None)
    EasyMock.expect(subProviders(1).artist).andReturn(Some(Value))
    whenExecuting(subProviders: _*) {
      assertEquals("Wrong result", Value, provider.artist.get)
    }
  }

  /**
   * Tests whether the album can be queried.
   */
  @Test def testAlbum() {
    EasyMock.expect(subProviders(0).album).andReturn(None)
    EasyMock.expect(subProviders(1).album).andReturn(Some(Value))
    whenExecuting(subProviders: _*) {
      assertEquals("Wrong result", Value, provider.album.get)
    }
  }

  /**
   * Tests whether the year can be queried.
   */
  @Test def testYear() {
    EasyMock.expect(subProviders(0).inceptionYearString).andReturn(None)
    EasyMock.expect(subProviders(1).inceptionYearString).andReturn(Some(Value))
    whenExecuting(subProviders: _*) {
      assertEquals("Wrong result", Value, provider.inceptionYearString.get)
    }
  }

  /**
   * Tests whether the track can be queried.
   */
  @Test def testTrack() {
    EasyMock.expect(subProviders(0).trackNoString).andReturn(None)
    EasyMock.expect(subProviders(1).trackNoString).andReturn(Some(Value))
    whenExecuting(subProviders: _*) {
      assertEquals("Wrong result", Value, provider.trackNoString.get)
    }
  }
}
