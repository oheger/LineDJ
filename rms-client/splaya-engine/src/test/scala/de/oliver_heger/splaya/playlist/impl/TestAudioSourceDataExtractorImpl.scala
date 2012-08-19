package de.oliver_heger.splaya.playlist.impl

import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.scalatest.junit.JUnitSuite
import de.oliver_heger.splaya.fs.impl.FSServiceImpl
import de.oliver_heger.splaya.fs.FSService
import de.oliver_heger.splaya.osgiutil.ServiceWrapper
import de.oliver_heger.tsthlp.StreamDataGenerator
import de.oliver_heger.tsthlp.TestFileSupport
import org.junit.BeforeClass
import org.junit.Ignore

/**
 * Test class for ''AudioSourceDataExtractorImpl''.
 */
class TestAudioSourceDataExtractorImpl extends JUnitSuite with TestFileSupport {
  /** The extractor to be tested. */
  private var extractor: AudioSourceDataExtractorImpl = _

  @Before def setUp() {
    extractor = new AudioSourceDataExtractorImpl(
      TestAudioSourceDataExtractorImpl.fsService)
  }

  @After def tearDown() {
    removeTempFiles()
  }

  /**
   * Tests a successful extraction of audio source data.
   */
  @Test def testExtractAudioSourceDataSuccess() {
    import TestAudioSourceDataExtractorImpl._
    val data = extractor.extractAudioSourceData(RootURI, Test1).get
    assert(Interpret === data.artistName)
    assert(Title === data.title)
    assert(Duration === data.duration)
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
      TestAudioSourceDataExtractorImpl.RootURI,
      TestAudioSourceDataExtractorImpl.Test2).get
    assert(TestAudioSourceDataExtractorImpl.Test2 === data.title)
    assert(6734 === data.duration)
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
    val root = file.getParentFile.toURI.toString
    assert(None === extractor.extractAudioSourceData(root, file.getName))
  }

  /**
   * Tries to extract information from a non existing file.
   */
  @Test def testExtractAudioSourceDataFileNotFound() {
    assert(None === extractor.extractAudioSourceData("root", "a non existing file!"))
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
  /** Constant for the root URI. */
  private val RootURI = "res://"

  /** Constant for the name of test file 1. */
  private val Test1 = "test.mp3";

  /** Constant for the name of test file 2. */
  private val Test2 = "test2.mp3";

  /** Constant for the name of the interpret. */
  private val Interpret = "Testinterpret";

  /** Constant for the title. */
  private val Title = "Testtitle";

  /** Constant for the original duration of the audio file. */
  private val Duration = 10842L;

  /** The service wrapper for the file system service. */
  private var fsService: ServiceWrapper[FSService] = _

  @BeforeClass def setupBeforeClass() {
    fsService = initFSService()
  }

  /**
   * Creates and initializes a service wrapper for an FSService implementation.
   * @return the service wrapper
   */
  private def initFSService(): ServiceWrapper[FSService] = {
    val wrapper = new ServiceWrapper[FSService]
    val fsService = new FSServiceImpl
    fsService.activate()
    wrapper bind fsService
    wrapper
  }
}
