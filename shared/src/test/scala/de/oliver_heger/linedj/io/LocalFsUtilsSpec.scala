/*
 * Copyright 2015-2025 The Developers Team.
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

import java.nio.file.{Path, Paths}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''LocalFsUtils''.
  */
class LocalFsUtilsSpec extends AnyFlatSpec with Matchers:
  "LocalFsUtils" should "extract an extension from a file name" in:
    val File = "test.txt"

    LocalFsUtils extractExtension File should be("txt")

  it should "return the extension if a name contains multiple dots" in:
    val File = "/my/music/1. Test.Music.mp3"

    LocalFsUtils extractExtension File should be("mp3")

  it should "return an empty extension if a file does not have an extension" in:
    LocalFsUtils extractExtension "fileWithoutExtension" should be("")

  it should "extract the extension from a Path object" in:
    val File = Paths.get("some", "path", "test.txt")

    LocalFsUtils.extractExtension(File) should be("txt")
