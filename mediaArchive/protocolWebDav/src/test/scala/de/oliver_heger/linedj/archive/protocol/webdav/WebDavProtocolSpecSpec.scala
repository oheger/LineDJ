/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.archive.protocol.webdav

import com.github.cloudfiles.webdav.DavFileSystem
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.util.Timeout
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.util.Success

class WebDavProtocolSpecSpec extends AnyFlatSpec with Matchers {
  "WebDavProtocolSpec" should "return the correct name" in {
    val spec = new WebDavProtocolSpec

    spec.name should be("webdav")
  }

  it should "return the correct multi host flag" in {
    val spec = new WebDavProtocolSpec

    spec.requiresMultiHostSupport shouldBe false
  }

  it should "create a correct file system" in {
    val RootPath = "https://my-archive.example.org/music"
    val timeout = Timeout(30.seconds)
    val spec = new WebDavProtocolSpec

    spec.createFileSystemFromConfig(RootPath, timeout) match {
      case Success(fs) =>
        fs.rootPath should be(Uri.Path("/music"))
        fs.fileSystem match {
          case dav: DavFileSystem =>
            dav.config.timeout should be(timeout)
            dav.config.rootUri should be(Uri(RootPath))
          case other => fail("Unexpected file system: " + other)
        }
      case r => fail("Unexpected result: " + r)
    }
  }
}
