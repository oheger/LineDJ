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

package de.oliver_heger.linedj.archive.protocol.onedrive

import com.github.cloudfiles.core.http.UriEncodingHelper
import com.github.cloudfiles.onedrive.{OneDriveConfig, OneDriveFileSystem}
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.util.Timeout
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
  * Test class for ''OneDriveProtocolSpec''.
  */
class OneDriveProtocolSpecSpec extends AnyFlatSpec with Matchers {
  "OneDriveProtocolSpec" should "return the correct protocol name" in {
    val spec = new OneDriveProtocolSpec

    spec.name should be("onedrive")
  }

  it should "return the correct multi-host flag" in {
    val spec = new OneDriveProtocolSpec

    spec.requiresMultiHostSupport shouldBe true
  }

  it should "create a correct file system" in {
    val DriveID = "myOneDriveID"
    val RootPath = "/the/archive/path"
    val TestTimeout = Timeout(11.seconds)
    val spec = new OneDriveProtocolSpec

    spec.createFileSystemFromConfig(DriveID + RootPath, TestTimeout) match {
      case Success(fs) =>
        fs.rootPath should be(Uri.Path(RootPath))
        fs.fileSystem match {
          case oneDrive: OneDriveFileSystem =>
            oneDrive.config.driveID should be(DriveID)
            oneDrive.config.serverUri should be(OneDriveConfig.OneDriveServerUri)
            oneDrive.config.optRootPath should be(Some(RootPath))
            oneDrive.config.timeout should be(TestTimeout)
          case f => fail("Unexpected file system: " + f)
        }
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "handle an archive URI does not contain a slash" in {
    val ArchiveUri = "justTheDriveID"
    val spec = new OneDriveProtocolSpec

    spec.createFileSystemFromConfig(ArchiveUri, Timeout(1.minute)) match {
      case Success(fs) =>
        fs.rootPath should be(Uri.Path.Empty)
        fs.fileSystem match {
          case oneDrive: OneDriveFileSystem =>
            oneDrive.config.driveID should be(ArchiveUri)
            oneDrive.config.optRootPath should be(None)
          case f => fail("Unexpected file system: " + f)
        }
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "handle an archive URI that contains only a slash at the end" in {
    val ArchiveUri = "justTheDriveID"
    val spec = new OneDriveProtocolSpec

    spec.createFileSystemFromConfig(ArchiveUri + UriEncodingHelper.UriSeparator, Timeout(1.minute)) match {
      case Success(fs) =>
        fs.rootPath should be(Uri.Path.Empty)
        fs.fileSystem match {
          case oneDrive: OneDriveFileSystem =>
            oneDrive.config.driveID should be(ArchiveUri)
            oneDrive.config.optRootPath should be(None)
          case f => fail("Unexpected file system: " + f)
        }
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "return a failure if the archive URI starts with a slash" in {
    val ArchiveUri = "/no/drive/ID"
    val spec = new OneDriveProtocolSpec

    spec.createFileSystemFromConfig(ArchiveUri, Timeout(10.minutes)) match {
      case Failure(exception) =>
        exception.getMessage should include(ArchiveUri)
      case r => fail("Unexpected result: " + r)
    }
  }
}
