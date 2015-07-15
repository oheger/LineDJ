package de.oliver_heger.splaya.mp3

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import org.junit.Assert._

/**
 * Test class for ''ID3TagProvider''.
 */
class TestID3TagProvider extends JUnitSuite {
  /**
   * Tests whether an existing inception year string can be converted.
   */
  @Test def testInceptionYearPresent() {
    val provider = new ID3TagProviderTestImpl(inceptionYearString = Some("1984"))
    assert(1984 === provider.inceptionYear.get)
  }

  /**
   * Tests whether a non-numeric inception year string is handled correctly.
   */
  @Test def testInceptionYearPresentNoNumber() {
    val provider = new ID3TagProviderTestImpl(
      inceptionYearString = Some("Nineteeneightyfour"))
    assertEquals("Got an inception year", None, provider.inceptionYear)
  }

  /**
   * Tests whether a non existing inception year is handled correctly.
   */
  @Test def testInceptionYearNotPresent() {
    val provider = new ID3TagProviderTestImpl
    assertEquals("Got an inception year", None, provider.inceptionYear)
  }

  /**
   * Tests whether a track number string can be converted if it is fully numeric.
   */
  @Test def testTrackNoExistingNumeric() {
    val provider = new ID3TagProviderTestImpl(trackNoString = Some("42"))
    assert(42 === provider.trackNo.get)
  }

  /**
   * Tests whether a partly numeric track number string can be converted.
   */
  @Test def testTrackNoExistingPartlyNumeric() {
    val provider = new ID3TagProviderTestImpl(trackNoString = Some("42 / 100"))
    assert(42 === provider.trackNo.get)
  }

  /**
   * Tests whether a track number string is handled correctly which is not a
   * number.
   */
  @Test def testTrackNoExistingNotNumeric() {
    val provider = new ID3TagProviderTestImpl(trackNoString = Some("NaN"))
    assertFalse("Got a track number", provider.trackNo.isDefined)
  }

  /**
   * Tests whether a non existing track number is handled correctly.
   */
  @Test def testTrackNoNonExisting() {
    val provider = new ID3TagProviderTestImpl
    assertFalse("Got a track number", provider.trackNo.isDefined)
  }

  /**
   * A concrete test implementation of ID3TagProvider.
   */
  private class ID3TagProviderTestImpl(val inceptionYearString: Option[String] = None,
    val trackNoString: Option[String] = None) extends ID3TagProvider {
    val title = None
    val artist = None
    val album = None
  }
}
