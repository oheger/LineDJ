/*
 * Copyright 2015-2020 The Developers Team.
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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''UriHelper''.
  */
class UriHelperSpec extends AnyFlatSpec with Matchers {
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

  it should "not modify a URI that already ends with a separator" in {
    val Uri = "/my/test/uri/"

    UriHelper.withTrailingSeparator(Uri) should be theSameInstanceAs Uri
  }

  it should "add a separator character to a URI if necessary" in {
    val Uri = "/not/ending/with/separator"

    UriHelper.withTrailingSeparator(Uri) should be(Uri + "/")
  }

  it should "not modify a URI that already starts with a separator" in {
    val Uri = "/uri/starting/with/separator"

    UriHelper.withLeadingSeparator(Uri) should be theSameInstanceAs Uri
  }

  it should "add a leading separator to a URI if necessary" in {
    val Uri = "uri/not/starting/with/separator"

    UriHelper.withLeadingSeparator(Uri) should be("/" + Uri)
  }

  it should "remove trailing characters from a string" in {
    val Path = "/test-uri"

    UriHelper.removeTrailing(Path + "////", "/") should be(Path)
  }

  it should "not modify a string if no trailing characters are removed" in {
    val Uri = "/noTrailingSlash"

    UriHelper.removeTrailing(Uri, "/") should be theSameInstanceAs Uri
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

  it should "not modify a string if no prefix can be removed" in {
    val Uri = "foo"

    UriHelper.removeLeading(Uri, "bar") should be(Uri)
  }

  it should "remove an existing prefix from a string" in {
    val Prefix = "https://"
    val Host = "test.org"

    UriHelper.removeLeading(Prefix + Host, Prefix) should be(Host)
  }

  it should "not change a URI that does not start with a leading separator" in {
    val Uri = "uri/without/leading/separator"

    UriHelper removeLeadingSeparator Uri should be(Uri)
  }

  it should "remove leading separators from a URI" in {
    val Uri = "uri/with/removed/separators"

    UriHelper removeLeadingSeparator "////" + Uri should be(Uri)
  }

  it should "report that a URI has a parent element" in {
    UriHelper hasParent "/foo/bar" shouldBe true
  }

  it should "detect a top-level URI without a slash" in {
    UriHelper hasParent "foo" shouldBe false
  }

  it should "detect a top-level URI with a leading slash" in {
    UriHelper hasParent "/foo" shouldBe false
  }

  it should "split a URI in a parent and a name component" in {
    val Parent = "/the/parent/uri"
    val Name = "name.txt"

    val (p, n) = UriHelper splitParent Parent + "/" + Name
    p should be(Parent)
    n should be(Name)
  }

  it should "handle a split operation for a top-level URI starting with a slash" in {
    val Name = "justAName"

    val (p, n) = UriHelper splitParent "/" + Name
    p should be("")
    n should be(Name)
  }

  it should "handle a split operation for a top-level URI without a slash" in {
    val Name = "nameOnly"

    val (p, n) = UriHelper splitParent Name
    p should be("")
    n should be(Name)
  }

  it should "handle a split operation if the URI ends with a separator" in {
    val Parent = "/parent"
    val Name = "child"

    val (p, n) = UriHelper splitParent Parent + "/" + Name + "/"
    p should be(Parent)
    n should be(Name)
  }

  it should "handle a split operation for a top-level URI ending with a slash" in {
    val Name = "top-level"

    val (p, n) = UriHelper splitParent Name + "/"
    p should be("")
    n should be(Name)
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

  it should "split a URI into its components" in {
    val uriComponents = Array("this", "is", "a", "uri")
    val uri = uriComponents.mkString(UriHelper.UriSeparator)

    UriHelper splitComponents uri should be(uriComponents)
  }

  it should "split a URI starting with a slash into its components" in {
    val uriComponents = Array("this", "is", "a", "uri")
    val uri = UriHelper.UriSeparator + uriComponents.mkString(UriHelper.UriSeparator)

    UriHelper splitComponents uri should be(uriComponents)
  }

  it should "generate a URI from its components" in {
    val Uri = "/a/uri/with/multiple/components"
    val components = UriHelper splitComponents Uri

    UriHelper fromComponents components should be(Uri)
  }

  it should "map the components of a URI" in {
    val Uri = "/the/test/uri"
    val Expected = "/the_/test_/uri_"

    val transformed = UriHelper.mapComponents(Uri)(_ + "_")
    transformed should be(Expected)
  }

  it should "encode the components of a URI" in {
    val Uri = "/a/test uri/to be/encoded"
    val Expected = "/a/test%20uri/to%20be/encoded"

    UriHelper encodeComponents Uri should be(Expected)
  }

  it should "decode the components of a URI" in {
    val Uri = "/a/test%20uri/to%20be/decoded"
    val Expected = "/a/test uri/to be/decoded"

    UriHelper decodeComponents Uri should be(Expected)
  }
}
