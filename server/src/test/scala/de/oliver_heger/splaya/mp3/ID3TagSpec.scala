package de.oliver_heger.splaya.mp3

import de.oliver_heger.splaya.FileTestHelper
import org.scalatest.{FlatSpec, Matchers}

/**
 * Test class for ''ID3Tag''.
 */
class ID3TagSpec extends FlatSpec with Matchers {
  "An ID3Tag" should "return an empty string if the tag has not content" in {
    val tag = ID3Tag("TAG", Array.empty)
    tag.asString should be("")
  }

  it should "return an empty string if the tag content is a single 0 byte" in {
    val tag = ID3Tag("TAG", Array(0.toByte))
    tag.asString should be("")
  }

  it should "handle the absence of an encoding byte" in {
    val text = "This is a test!"
    val tag = ID3Tag("Tag", text.getBytes)
    tag.asString should be(text)
  }

  it should "allow querying the tag content as byte array" in {
    val content = FileTestHelper.testBytes()
    val tag = ID3Tag("Tag", content)

    val data = tag.asBytes
    val data2 = tag.asBytes
    data should not be theSameInstanceAs(content)
    data should not be theSameInstanceAs(data2)
    data should be(content)
    data2 should be(data)
  }
}
