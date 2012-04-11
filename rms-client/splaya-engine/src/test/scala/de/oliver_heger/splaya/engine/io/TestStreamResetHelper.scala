package de.oliver_heger.splaya.engine.io

import org.scalatest.junit.JUnitSuite
import org.junit.Before
import org.junit.Test
import java.io.IOException
import org.scalatest.mock.EasyMockSugar
import org.easymock.EasyMock
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

/**
 * Test class for {@code StreamResetHelper}.
 */
class TestStreamResetHelper extends JUnitSuite with EasyMockSugar {
  /** Constant for a test text to be streamed.*/
  val Text = """Lorem ipsum dolor sit amet, consectetuer adipiscing elit.
    Aenean commodo ligula eget dolor. Aenean massa. Cum sociis natoque
    penatibus et magnis dis parturient montes, nascetur ridiculus mus. Donec
    quam felis, ultricies nec, pellentesque eu, pretium quis, sem. Nulla
    consequat massa quis enim. Donec pede justo, fringilla vel, aliquet nec,
    vulputate eget, arcu. In enim justo, rhoncus ut, imperdiet a, venenatis
    vitae, justo. Nullam dictum felis eu pede mollis pretium. Integer
    tincidunt. Cras dapibus."""

  /** The mock for the factory for temporary files.*/
  var factory: TempFileFactory = _

  /** The helper to be tested.*/
  var helper: StreamResetHelper = _

  @Before def initialize() {
    factory = mock[TempFileFactory]
    helper = new StreamResetHelper(factory)
  }

  /**
   * Returns a block of the test text.
   * @param start the start index
   * @param len the length of the block
   * @return an array with this text block
   */
  private def block(start: Int, len: Int): Array[Byte] =
    Text.substring(start, start + len).getBytes()

  @Test def testReadWithoutMark() {
    assert(helper.marked == false)
    val buf = new Array[Byte](100)
    assert(helper.read(buf, 0, 100) === 0)
  }

  @Test def testResetWithoutMark() {
    intercept[IOException] {
      helper.reset
    }
  }

  /**
   * Tests whether reset() works after a mark() operation.
   */
  @Test def testMarkAndReset() {
    val temp = mock[TempFile]
    val os = new ByteArrayOutputStream
    val is = new ByteArrayInputStream(block(10, 50))
    expecting {
      EasyMock.expect(factory.createFile).andReturn(temp)
      EasyMock.expect(temp.outputStream).andReturn(os)
      EasyMock.expect(temp.inputStream).andReturn(is)
      EasyMock.expect(temp.delete).andReturn(true)
    }

    whenExecuting(factory, temp) {
      helper.push(block(0, 10), 0, 10);
      helper.mark
      assert(helper.read(new Array[Byte](50), 10, 50) === 0)
      helper.push(block(10, 50), 0, 50)
      helper.reset
      val buf = new Array[Byte](25)
      assert(helper.read(buf, 0, 25) === 25)
      val s1 = new String(buf)
      assert(helper.read(buf, 0, 25) === 25)
      val s2 = new String(buf)
      assert(helper.read(buf, 0, 25) === 0)
      assert(Text.substring(10, 60) === s1 + s2)
      helper.close
    }
    assert(Text.substring(10, 60) === os.toString())
  }

  /**
   * Tests whether another mark() operation overrides a previous one.
   */
  @Test def testMarkMultipleTimes() {
    val temp1 = mock[TempFile]
    val temp2 = mock[TempFile]
    val os1 = new ByteArrayOutputStream
    val os2 = new ByteArrayOutputStream
    val is = new ByteArrayInputStream(block(20, 10))
    expecting {
      EasyMock.expect(factory.createFile).andReturn(temp1)
      EasyMock.expect(temp1.outputStream).andReturn(os1)
      EasyMock.expect(temp1.delete).andReturn(true)
      EasyMock.expect(factory.createFile).andReturn(temp2)
      EasyMock.expect(temp2.outputStream).andReturn(os2)
      EasyMock.expect(temp2.inputStream).andReturn(is)
      EasyMock.expect(temp2.delete).andReturn(true)
    }

    whenExecuting(factory, temp1, temp2) {
      helper.mark()
      helper.push(block(0, 20), 0, 20)
      helper.mark()
      helper.push(block(20, 20), 0, 10)
      helper.reset()
      val buf = new Array[Byte](20)
      assert(helper.read(buf, 0, 20) === 10)
      helper.close()
    }
    assert(Text.substring(20, 30) === os2.toString)
  }

  /**
   * Tests whether multiple mark/reset operations are handled correctly.
   */
  @Test def testMarkAndResetMultipleTimes() {
    val temp1 = mock[TempFile]
    val temp2 = mock[TempFile]
    val os1 = new ByteArrayOutputStream
    val os2 = new ByteArrayOutputStream
    val is1 = new ByteArrayInputStream(block(0, 20))
    val is2 = new ByteArrayInputStream(block(10, 50))
    expecting {
      EasyMock.expect(factory.createFile).andReturn(temp1)
      EasyMock.expect(temp1.outputStream).andReturn(os1)
      EasyMock.expect(temp1.inputStream).andReturn(is1)
      EasyMock.expect(factory.createFile).andReturn(temp2)
      EasyMock.expect(temp2.outputStream).andReturn(os2)
      EasyMock.expect(temp2.inputStream).andReturn(is2)
      EasyMock.expect(temp1.delete).andReturn(true)
      EasyMock.expect(temp2.delete).andReturn(true)
    }

    whenExecuting(factory, temp1, temp2) {
      helper.mark()
      helper.push(block(0, 20), 0, 20)
      helper.reset()
      var buf = new Array[Byte](10)
      assert(helper.read(buf, 0, 10) === 10)
      helper.mark()
      buf = new Array[Byte](20)
      assert(helper.read(buf, 0, 20) === 10)
      helper.push(block(10, 50), 0, 50)
      helper.reset()
      assert(helper.read(buf, 0, 20) === 20)
      assert(Text.substring(10, 30) === new String(buf))
      helper.close()
    }
  }
}