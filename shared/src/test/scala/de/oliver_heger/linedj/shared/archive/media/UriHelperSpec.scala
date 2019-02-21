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

package de.oliver_heger.linedj.shared.archive.media

import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''UriHelper''.
  */
class UriHelperSpec extends FlatSpec with Matchers {
  "UriHelper" should "normalize an already normalized URI" in {
    val Uri = "https://music.org/test/song.mp3"

    UriHelper normalize Uri should be(Uri)
  }

  it should "normalize a URI using back slashes" in {
    val Uri = "C:\\data\\music\\test\\song.mp3"

    UriHelper normalize Uri should be("C:/data/music/test/song.mp3")
  }

  it should "remove a file extension from a URI" in {
    val Extension = ".mp3"
    val Uri = "https://my-music.la/songs/title"

    UriHelper removeExtension Uri + Extension should be(Uri)
  }

  it should "handle URIs without an extension when an extension is to be removed" in {
    val Uri = "uriWithoutExtension"

    UriHelper removeExtension Uri should be(Uri)
  }

  it should "remove a file extension only from the file component" in {
    val Uri = "https://my-music.la/songs/title"

    UriHelper removeExtension Uri should be(Uri)
  }

  it should "handle a URI without a trailing slash when removing the trailing separator" in {
    val Uri = "http://music.org/test"

    UriHelper removeTrailingSeparator Uri should be(Uri)
  }

  it should "remove a trailing separator from a URI" in {
    val Uri = "http://music.org/test"

    UriHelper removeTrailingSeparator Uri + "/" should be(Uri)
  }

  it should "handle a URI consisting only of a separator when removing a trailing separator" in {
    UriHelper removeTrailingSeparator "/" should be("")
  }

  it should "return the name component of an URI" in {
    val Name = "myFileName.txt"
    val Uri = "https://www.some-data.org/texts/" + Name

    UriHelper extractName Uri should be(Name)
  }

  it should "return the name of an URI that consists only of a name component" in {
    val Uri = "someName.dat"

    UriHelper extractName Uri should be(Uri)
  }

  it should "handle a URI starting with a slash when extracting the name" in {
    val Uri = "someSimpleName"

    UriHelper extractName "/" + Uri should be(Uri)
  }

  it should "extract the name from an empty URI" in {
    UriHelper extractName "" should be("")
  }

  it should "extract the parent URI from a URI" in {
    val Parent = "https://www.a-uri.org/folder"
    val Uri = Parent + "/someFile.dat"

    UriHelper extractParent Uri should be(Parent)
  }

  it should "extract the parent URI from a URI that has no parent" in {
    UriHelper extractParent "uriWithoutParent.txt" should be("")
  }

  it should "extract the parent from a URI starting with the only slash" in {
    UriHelper extractParent "/uriWithoutParent.txt" should be("")
  }

  it should "extract he parent from an empty URI" in {
    UriHelper extractParent "" should be("")
  }

  it should "URL-decode a URI" in {
    val Uri = "https://www.foo%20bar.com/My%20test%20song%20%28nice%29%2A%2b%2C%2d%2E%2F.mp3"
    val ExpUri = "https://www.foo bar.com/My test song (nice)*+,-./.mp3"

    UriHelper urlDecode Uri should be(ExpUri)
  }

  it should "only URL-decode a URI if necessary" in {
    val uris = List("Song + Test = 80 %", "%xy", "% 100", "%20Test%20%%30", "%1")

    uris foreach { uri =>
      UriHelper urlDecode uri should be(uri)
    }
  }

  it should "URL-encode a URI" in {
    val Uri = "My (cool) URI!"
    val ExpUri = "My%20%28cool%29%20URI%21"

    UriHelper urlEncode Uri should be(ExpUri)
  }

  it should "concatenate two URI components" in {
    val c1 = "foo"
    val c2 = "bar"
    val ExpUri = c1 + "/" + c2

    UriHelper.concat(c1, c2) should be(ExpUri)
  }

  it should "concatenate two URI components if the second one is empty" in {
    val Comp = "first"

    UriHelper.concat(Comp, "") should be(Comp + "/")
  }

  it should "concatenate two URI components if the first one is empty" in {
    val Comp = "second"

    UriHelper.concat("", Comp) should be(Comp)
  }

  it should "concatenate two empty URI components" in {
    UriHelper.concat("", "") should be("")
  }
}
