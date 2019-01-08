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

package de.oliver_heger.linedj.archivehttp.impl.io

import akka.http.scaladsl.model.Uri
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''UriUtils''.
  */
class UriUtilsSpec extends FlatSpec with Matchers {
  "UriUtils" should "split a URI into path components" in {
    val uri = Uri("https://my.online-music.org/my%20music/test/data.json")

    val components = UriUtils.uriComponents(uri.toString())
    components should contain theSameElementsInOrderAs Seq("my%20music", "test", "data.json")
  }

  it should "correctly split a relative URI" in {
    val uri = "/this/is/a/relative/test/uri.html"

    val components = UriUtils.uriComponents(uri)
    components should contain theSameElementsInOrderAs Seq("this", "is", "a", "relative",
      "test", "uri.html")
  }

  it should "split a complex URI with escaped characters into path components" in {
    val uri = "TangerineDream2/2%20-%20Purgatorio%20CD%201%20%282004%29/" +
      "01%20-%20Tangerine%20Dream%20-%20%20Above%20the%20Great%20Dry%20Land.mp3"

    val components = UriUtils.uriComponents(uri)
    components should contain theSameElementsInOrderAs Seq("TangerineDream2",
      "2%20-%20Purgatorio%20CD%201%20%282004%29",
      "01%20-%20Tangerine%20Dream%20-%20%20Above%20the%20Great%20Dry%20Land.mp3")
  }

  it should "create a relative URI if there is no common prefix with base components" in {
    val base = Seq("base", "path", "content.json")
    val uri = "/other/path/index.html"

    val relUri = UriUtils.relativeUri(base, uri)
    relUri should be(uri drop 1)
  }

  it should "create a relative URI against a base URI that starts with a slash" in {
    val base = Seq("music", "test-archive", "content.json")
    val uri = "/music/test-archive/medium/artist/album/song.mp3"

    val relUri = UriUtils.relativeUri(base, uri)
    relUri should be("medium/artist/album/song.mp3")
  }

  it should "create a relative URI against a base URI that does not start with a slash" in {
    val base = Seq("music", "test-archive", "content.json")
    val uri = "music/test-archive/medium/artist/album/song.mp3"

    val relUri = UriUtils.relativeUri(base, uri)
    relUri should be("medium/artist/album/song.mp3")
  }

  it should "resolve a relative URI that starts with a slash" in {
    val baseUri = Uri("https://my.online-music.org/music/test/data.json")
    val relUri = "/medium/artist/album/song.mp3"
    val expUri = Uri("/medium/artist/album/song.mp3")

    val resolvedUri = UriUtils.resolveUri(baseUri, relUri)
    resolvedUri should be(expUri)
  }

  it should "resolve a relative URI that does not start with a slash" in {
    val baseUri = Uri("https://my.online-music.org/music/test/data.json")
    val relUri = "medium/artist/album/song.mp3"
    val expUri = Uri("/music/test/medium/artist/album/song.mp3")

    val resolvedUri = UriUtils.resolveUri(baseUri, relUri)
    resolvedUri should be(expUri)
  }
}
