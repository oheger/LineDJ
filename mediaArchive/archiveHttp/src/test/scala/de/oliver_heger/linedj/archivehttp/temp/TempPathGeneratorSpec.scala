/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.temp

import java.nio.file.Paths
import java.time.Instant

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object TempPathGeneratorSpec {
  /** A root path for temporary files. */
  private val Root = Paths get "downloadTempFiles"

  /** Name of a test archive. */
  private val Archive = "CoolMusicRemote"
}

/**
  * Test class for ''TempPathGenerator''.
  */
class TempPathGeneratorSpec extends AnyFlatSpec with Matchers {

  import TempPathGeneratorSpec._

  "A TempPathGenerator" should "generate a valid sub directory for an archive" in {
    val generator = TempPathGenerator(Root)

    val path = generator generateArchivePath Archive
    path.toString should include(Archive)
    path.getParent should be(Root)
  }

  it should "generate other directories depending on the start time" in {
    val time1 = Instant ofEpochSecond 42
    val time2 = Instant ofEpochSecond 41
    val gen1 = TempPathGenerator(Root, time1)
    val gen2 = TempPathGenerator(Root, time2)

    val path1 = gen1 generateArchivePath Archive
    val path2 = gen2 generateArchivePath Archive
    path1 should not be path2
  }

  it should "generate a valid path for a temporary download file" in {
    val gen = TempPathGenerator(Root)

    val path = gen.generateDownloadPath(Archive, 1, 1)
    path.getParent should be(gen.generateArchivePath(Archive))
    path.toString should endWith(".tmp")
  }

  it should "generate different download files for different downloads" in {
    val gen = TempPathGenerator(Root)

    val path1 = gen.generateDownloadPath(Archive, 1, 1)
    val path2 = gen.generateDownloadPath(Archive, 2, 1)
    path1 should not be path2
  }

  it should "generate different download files for different file indices" in {
    val gen = TempPathGenerator(Root)

    val path1 = gen.generateDownloadPath(Archive, 1, 1)
    val path2 = gen.generateDownloadPath(Archive, 1, 2)
    path1 should not be path2
  }

  it should "separate download index from file index" in {
    val gen = TempPathGenerator(Root)

    val path1 = gen.generateDownloadPath(Archive, 11, 1)
    val path2 = gen.generateDownloadPath(Archive, 1, 11)
    path1 should not be path2
  }

  it should "detect a removable path2" in {
    val gen1 = TempPathGenerator(Root, Instant ofEpochSecond 1)
    val gen2 = TempPathGenerator(Root, Instant ofEpochSecond 2)
    val path = gen2 generateArchivePath Archive
    val file = gen2.generateDownloadPath(Archive, 2, 3)

    gen1 isRemovableTempPath path shouldBe true
    gen1 isRemovableTempPath file shouldBe true
  }

  it should "reject a removable path for the current run" in {
    val gen = TempPathGenerator(Root)
    val path = gen generateArchivePath Archive

    gen isRemovableTempPath path shouldBe false
  }

  it should "not remove an unrelated file" in {
    val gen = TempPathGenerator(Root)
    val path = Root resolve "anotherFile.tmp"

    gen isRemovableTempPath path shouldBe false
  }

  it should "not remove an unrelated file in a download temp directory" in {
    val gen1 = TempPathGenerator(Root, Instant ofEpochSecond 1)
    val dir = gen1 generateArchivePath Archive
    val path = dir resolve "noDownloadFile.tmp"
    val gen = TempPathGenerator(Root, Instant ofEpochSecond 2)

    gen isRemovableTempPath path shouldBe false
  }
}
