package de.oliver_heger.splaya.playlist.impl

import de.oliver_heger.splaya.tsthlp.TestFileSupport
import org.scalatest.junit.JUnitSuite
import org.apache.commons.vfs2.FileSystemManager
import org.junit.BeforeClass
import org.apache.commons.vfs2.VFS
import org.junit.Before
import org.junit.Test
import org.junit.Assert._
import de.oliver_heger.splaya.tsthlp.StreamDataGenerator
import org.junit.After
import de.oliver_heger.splaya.engine.io.SourceResolverImpl

/**
 * Test class for ''AudioSourceDataExtractorImpl''.
 */
class TestAudioSourceDataExtractorImpl extends JUnitSuite with TestFileSupport {
  /** The extractor to be tested. */
  private var extractor: AudioSourceDataExtractorImpl = _

  @Before def setUp() {
    extractor = new AudioSourceDataExtractorImpl(new SourceResolverImpl(
      TestAudioSourceDataExtractorImpl.manager))
  }

  @After def tearDown() {
    removeTempFiles()
  }

  /**
   * Tests a successful extraction of audio source data.
   */
  @Test def testExtractAudioSourceDataSuccess() {
    import TestAudioSourceDataExtractorImpl._
    val data = extractor.extractAudioSourceData(Test1).get
    assert(Interpret === data.artistName)
    assert(Title === data.title)
    assert(DurationInSec === data.duration)
    assert(2006 === data.inceptionYear)
    assert(1 === data.trackNo)
    assert("A Test Collection" === data.albumName)
  }

  /**
   * Tests an audio file with no ID3 tags. At least the duration should be
   * available and a title derived from the URI.
   */
  @Test def testExtractAudioSourceDataNoProperties() {
    val data = extractor.extractAudioSourceData(
      TestAudioSourceDataExtractorImpl.Test2).get
    assert(TestAudioSourceDataExtractorImpl.Test2 === data.title)
    assert(7 === data.duration)
    assertNull("Got an album", data.albumName)
  }

  /**
   * Tests whether an unsupported audio file is handled correctly.
   */
  @Test def testExtractAudioSourceDataUnsupported() {
    val generator = StreamDataGenerator()
    val file = createTempFile { out =>
      out.print(generator.generateStreamContent(0, 1024))
    }
    assert(None === extractor.extractAudioSourceData(file.toURI.toString))
  }

  /**
   * Tries to extract information from a non existing file.
   */
  @Test def testExtractAudioSourceDataFileNotFound() {
    assert(None === extractor.extractAudioSourceData("a non existing file!"))
  }

  /**
   * Tests whether undefined numeric properties are handled correctly.
   */
  @Test def testUndefinedNumericProperties() {
    val map = new java.util.HashMap[String, Object]
    val data = extractor.createSourceDataFromProperties(map,
      TestAudioSourceDataExtractorImpl.Test1)
    assert(0 === data.trackNo)
    assert(0 === data.duration)
  }

  /**
   * Tests whether an unexpected property value is handled correctly.
   */
  @Test def testInvalidNumericPropertyValue() {
    val map = new java.util.HashMap[String, Object]
    map.put("mp3.id3tag.track", "not a number")
    val data = extractor.createSourceDataFromProperties(map,
      TestAudioSourceDataExtractorImpl.Test1)
    assert(0 === data.trackNo)
  }
}

object TestAudioSourceDataExtractorImpl {
  /** Constant for the name of test file 1. */
  private val Test1 = "res:test.mp3";

  /** Constant for the name of test file 2. */
  private val Test2 = "res:test2.mp3";

  /** Constant for the name of the interpret. */
  private val Interpret = "Testinterpret";

  /** Constant for the title. */
  private val Title = "Testtitle";

  /** Constant for the original duration of the audio file. */
  private val Duration = 10842L;

  /** Constant for the duration of the audio file in seconds. */
  private val DurationInSec = 11L;

  /** The VFS file system manager. */
  private var manager: FileSystemManager = _

  @BeforeClass def setUpBeforeClass() {
    manager = VFS.getManager()
  }
}
