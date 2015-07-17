package de.oliver_heger.splaya.media

import java.io.{ByteArrayOutputStream, OutputStreamWriter}

import de.oliver_heger.splaya.FileTestHelper
import org.scalatest.{FlatSpec, Matchers}

object MediumInfoParserSpec {
  /** A test medium URI. */
  private val MediumURI = "test://TestMediumURI.tst"

  /** A test medium name. */
  private val MediumName = "TestMedium"

  /** A test description for the test medium. */
  private val MediumDescription = "A description for the test medium."

  /** A test order mode. */
  private val OrderMode = "TestOrder"

  /**
   * Generates a XML fragment with test playlist settings.
   * @return the fragment
   */
  private def createMediumSettings() =
    <configuration>
      <name>{MediumName}</name>
      <description>{MediumDescription}</description>
      <order>
        <mode>{OrderMode}</mode>
        <params>
          {createOrderParams()}
        </params>
      </order>
    </configuration>

  /**
   * Generates a XML fragment with parameters for the ordering.
   * @return the fragment
   */
  private def createOrderParams() =
    <coolness>true</coolness>

  /**
   * Creates the content array for the test medium settings.
   * @return an array with the content of the test medium settings
   */
  private def createMediumSettingsFileContent(): Array[Byte] = {
    val stream = new ByteArrayOutputStream
    val writer = new OutputStreamWriter(stream)
    xml.XML.write(writer, createMediumSettings(), enc = null, xmlDecl = false, doctype = null)
    writer.close()
    stream.toByteArray
  }
}

/**
 * Test class for ''MediumInfoParser''.
 */
class MediumInfoParserSpec extends FlatSpec with Matchers {

  import MediumInfoParserSpec._

  "A MediumInfoParser" should "parse a valid description file" in {
    val parser = new MediumInfoParser
    val info = parser.parseMediumInfo(createMediumSettingsFileContent(), MediumURI).get

    info.mediumURI should be(MediumURI)
    info.name should be(MediumName)
    info.description should be(MediumDescription)
    info.orderMode should be(OrderMode)
    info.orderParams.size should be(1)
    info.orderParams.head.label should be("coolness")
    info.orderParams.head.text should be("true")
  }

  it should "handle exceptions when parsing XML" in {
    val Content = "<configuration><name>Invalid</name><description>test</configuration>"
    val parser = new MediumInfoParser

    parser.parseMediumInfo(FileTestHelper.toBytes(Content), MediumURI) should be(None)
  }
}
