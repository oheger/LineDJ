/*
 * Copyright 2015-2019 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.oliver_heger.linedj.archivecommon.uri

import de.oliver_heger.linedj.shared.archive.media.MediumID
import org.scalatest.{FlatSpec, Matchers}

/**
  * Implementation of the mapping spec.
  */
case class UriMappingSpecImpl(override val prefixToRemove: String,
                              override val pathComponentsToRemove: Int,
                              override val uriTemplate: String,
                              override val uriPathSeparator: String,
                              override val urlEncoding: Boolean) extends UriMappingSpec

object UriMapperSpec {
  /** Path to the test medium. */
  private val MediumPath = "/test/Prince1"

  /** The default prefix for URIs. */
  private val UriPrefix = "path://"

  /** String for the prefix path components that are to be removed. */
  private val PrefixComponents = "test/medium/"

  /** A prefix containing path components to be removed. */
  private val PrefixWithPaths = UriPrefix + PrefixComponents

  /** A default mapping configuration. */
  private val MappingConfig = UriMappingSpecImpl(prefixToRemove = UriPrefix,
    uriTemplate = "${medium}/songs/${uri}", urlEncoding = false,
    uriPathSeparator = null, pathComponentsToRemove = 2)

  /** A test medium ID. */
  private val TestMedium = MediumID("someMedium", Some(MediumPath + "/playlist.settings"))
}

/**
  * Test class for ''UriMapper''.
  */
class UriMapperSpec extends FlatSpec with Matchers {

  import UriMapperSpec._

  /**
    * Helper method for mapping a test URI with the given parameters.
    *
    * @param u      the URI to be mapped
    * @param config the mapping config
    * @param mid    the medium ID
    * @return the resulting URI
    */
  private def mapUri(u: String, config: UriMappingSpec = MappingConfig,
                     mid: MediumID = TestMedium): Option[String] = {
    val mapper = new UriMapper
    mapper.mapUri(config, mid, u)
  }

  "A UriMapper" should "return None if there is no prefix match" in {
    mapUri("other://song.mp3") shouldBe 'empty
  }

  it should "apply a template correctly" in {
    val uri = PrefixWithPaths + "Prince - (1988) - Lovesexy/03 - Glam Slam.mp3"
    val exp = MediumPath + "/songs/Prince - (1988) - Lovesexy/03 - Glam Slam.mp3"

    mapUri(uri).get should be(exp)
  }

  it should "apply a template correctly if the URI path starts with a slash" in {
    val uri = UriPrefix + "/" + PrefixComponents + "Prince - (1988) - Lovesexy/03 - Glam Slam.mp3"
    val exp = MediumPath + "/songs/Prince - (1988) - Lovesexy/03 - Glam Slam.mp3"
    val mapConfig = MappingConfig.copy(uriTemplate = "${medium}/songs${uri}")

    mapUri(uri, config = mapConfig).get should be(exp)
  }

  it should "handle a medium ID without a settings path gracefully" in {
    val uri = PrefixWithPaths + "song.mp3"
    val exp = "/songs/song.mp3"

    mapUri(uri, mid = TestMedium.copy(mediumDescriptionPath = None)).get should be(exp)
  }

  it should "handle a medium path with only a file component" in {
    val uri = UriPrefix + "song.mp3"
    val exp = "/songs/song.mp3"

    mapUri(uri, mid = TestMedium.copy(mediumDescriptionPath = Some("foo"))).get should be(exp)
  }

  it should "support an undefined remove prefix" in {
    val uri = "someFile.mp3"
    val exp = MediumPath + "/songs/someFile.mp3"

    mapUri(uri, config = MappingConfig.copy(prefixToRemove = null,
      pathComponentsToRemove = 0)).get should be(exp)
  }

  it should "apply URL encoding" in {
    val uri = UriPrefix + "a/test song.mp3"
    val exp = MediumPath + "/songs/a%2Ftest%20song.mp3"

    mapUri(uri, config = MappingConfig.copy(urlEncoding = true)).get should be(exp)
  }

  it should "URL encode URIs with multiple path components" in {
    val uri = PrefixWithPaths + "Prince - (1988) - Lovesexy/03 - Glam Slam.mp3"
    val exp = MediumPath + "/songs/Prince%20-%20%281988%29%20-%20Lovesexy/03%20-%20Glam%20Slam.mp3"
    val config = MappingConfig.copy(urlEncoding = true, uriPathSeparator = "/")

    mapUri(uri, config = config).get should be(exp)
  }

  it should "quote the path separator character when used in split" in {
    val uri = UriPrefix + PrefixComponents.replace('/', '\\') +
      "Prince - (1988) - Lovesexy\\03 - Glam Slam.mp3"
    val exp = MediumPath + "/songs/Prince%20-%20%281988%29%20-%20Lovesexy/03%20-%20Glam%20Slam.mp3"
    val config = MappingConfig.copy(urlEncoding = true, uriPathSeparator = "\\")

    mapUri(uri, config = config).get should be(exp)
  }

  it should "handle a URL ending on a separator as input" in {
    val uri = PrefixWithPaths + "special-songs/"
    val exp = MediumPath + "/songs/special-songs/"
    val config = MappingConfig.copy(pathComponentsToRemove = 42)

    mapUri(uri, config = config).get should be(exp)
  }

  it should "handle a null URI as input" in {
    mapUri(null) shouldBe 'empty
  }
}
