package de.oliver_heger.splaya.playlist.impl

import java.io.IOException
import java.io.InputStream

import org.easymock.EasyMock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar

import de.oliver_heger.splaya.fs.FSService
import de.oliver_heger.splaya.fs.StreamSource
import de.oliver_heger.splaya.osgiutil.ServiceWrapper
import de.oliver_heger.splaya.AudioSourceData
import de.oliver_heger.splaya.MediaDataExtractor

/**
 * Test class for ''AudioSourceDataExtractorImpl''.
 */
class TestAudioSourceDataExtractorImpl extends JUnitSuite with EasyMockSugar {
  /** Constant for the root URI. */
  private val RootURI = "res://SomeRootURI"

  /** Constant for the name of a test file. */
  private val TestURI = "test.mp3";

  /** A mock for the FS service. */
  private var fsService: FSService = _

  /** A mock for a first media extractor. */
  private var dataExtr1: MediaDataExtractor = _

  /** A mock for another media extractor. */
  private var dataExtr2: MediaDataExtractor = _

  /** The extractor to be tested. */
  private var extractor: AudioSourceDataExtractorImpl = _

  @Before def setUp() {
    val wrapper = new ServiceWrapper[FSService]
    fsService = mock[FSService]
    wrapper bind fsService
    extractor = new AudioSourceDataExtractorImpl(wrapper)
    dataExtr1 = mock[MediaDataExtractor]
    dataExtr2 = mock[MediaDataExtractor]
  }

  /**
   * Prepares the mock for the file system service to resolve the test audio
   * source.
   * @param stream the input stream to be returned by the audio source
   */
  private def prepareFSService(stream: InputStream) {
    val source = mock[StreamSource]
    EasyMock.expect(fsService.resolve(RootURI, TestURI)).andReturn(source)
    EasyMock.expect(source.openStream()).andReturn(stream).anyTimes()
    EasyMock.replay(source)
  }

  /**
   * Creates a mock for an input stream and prepare the mock for the file
   * system service to resolve the test audio source.
   * @return the mock for the input stream
   */
  private def createStreamAndPrepareFSService(): InputStream = {
    val stream = mock[InputStream]
    prepareFSService(stream)
    stream.close()
    EasyMock.expectLastCall[Unit].times(1, 2)
    stream
  }

  /**
   * Tests a successful extraction of audio source data.
   */
  @Test def testExtractAudioSourceDataSuccess() {
    val stream = createStreamAndPrepareFSService()
    val data = mock[AudioSourceData]
    EasyMock.expect(dataExtr1.extractData(stream)).andReturn(Some(data))
    EasyMock.expect(dataExtr2.extractData(stream)).andReturn(None).times(0, 1)
    whenExecuting(fsService, stream, dataExtr1, dataExtr2) {
      extractor addMediaDataExtractor dataExtr1
      extractor addMediaDataExtractor dataExtr2
      assertSame("Wrong data", data, extractor.extractAudioSourceData(
        RootURI, TestURI).get)
    }
  }

  /**
   * Tests an extraction if none of the media extractors can handle the source.
   */
  @Test def testExtractUnsupportedSource() {
    val stream = createStreamAndPrepareFSService()
    EasyMock.expect(dataExtr1.extractData(stream)).andReturn(None)
    EasyMock.expect(dataExtr2.extractData(stream)).andReturn(None)
    whenExecuting(fsService, stream, dataExtr1, dataExtr2) {
      extractor addMediaDataExtractor dataExtr1
      extractor addMediaDataExtractor dataExtr2
      assertFalse("Got data", extractor.extractAudioSourceData(
        RootURI, TestURI).isDefined)
    }
  }

  /**
   * Tests an extraction if there are no media extractors.
   */
  @Test def testExtractNoMediaExtractors() {
    whenExecuting(fsService) {
      assertFalse("Got data", extractor.extractAudioSourceData(
        RootURI, TestURI).isDefined)
    }
  }

  /**
   * Tests an extract operation if an extractor throws an exception.
   */
  @Test def testExtractException() {
    val stream = createStreamAndPrepareFSService()
    EasyMock.expect(dataExtr1.extractData(stream)).andThrow(new RuntimeException)
    EasyMock.expect(dataExtr2.extractData(stream)).andReturn(None)
    whenExecuting(fsService, stream, dataExtr1, dataExtr2) {
      extractor addMediaDataExtractor dataExtr1
      extractor addMediaDataExtractor dataExtr2
      assertFalse("Got data", extractor.extractAudioSourceData(
        RootURI, TestURI).isDefined)
    }
  }

  /**
   * Tests whether media extractors can be removed.
   */
  @Test def testRemoveMediaDataExtractor() {
    val stream = createStreamAndPrepareFSService()
    EasyMock.expect(dataExtr1.extractData(stream)).andReturn(None)
    whenExecuting(fsService, stream, dataExtr1, dataExtr2) {
      extractor addMediaDataExtractor dataExtr1
      extractor addMediaDataExtractor dataExtr2
      extractor removeMediaDataExtractor dataExtr2
      assertFalse("Got data", extractor.extractAudioSourceData(
        RootURI, TestURI).isDefined)
    }
  }

  /**
   * Tests whether an exception thrown by the stream on close() is detected.
   */
  @Test def testStreamExceptionOnClose() {
    val stream = mock[InputStream]
    prepareFSService(stream)
    val data = mock[AudioSourceData]
    EasyMock.expect(dataExtr1.extractData(stream)).andReturn(Some(data))
    stream.close()
    EasyMock.expectLastCall[Unit].andThrow(
      new IOException("TestException on close()!"))
    whenExecuting(fsService, stream, dataExtr1) {
      extractor addMediaDataExtractor dataExtr1
      assertSame("Wrong data", data, extractor.extractAudioSourceData(
        RootURI, TestURI).get)
    }
  }
}
