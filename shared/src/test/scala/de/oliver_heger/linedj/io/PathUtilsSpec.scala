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

package de.oliver_heger.linedj.io

import java.nio.file.Path

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''PathUtils''.
  */
class PathUtilsSpec extends AnyFlatSpec with Matchers {
  "PathUtils" should "extract an extension from a file name" in {
    val File = "test.txt"

    PathUtils extractExtension File should be("txt")
  }

  it should "return the extension if a name contains multiple dots" in {
    val File = "/my/music/1. Test.Music.mp3"

    PathUtils extractExtension File should be("mp3")
  }

  it should "return an empty extension if a file does not have an extension" in {
    PathUtils extractExtension "fileWithoutExtension" should be("")
  }

  it should "convert a string to a Path" in {
    val Name = "music.mp3"

    val path = PathUtils asPath Name
    path.getFileName.toString should be(Name)
  }

  it should "enable implicit conversions from string to Path" in {
    val Name = "testFile.txt"

    import PathUtils._
    val path: Path = Name
    path.toString should be(Name)
  }
}
