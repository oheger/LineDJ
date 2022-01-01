/*
 * Copyright 2015-2022 The Developers Team.
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

import akka.util.Timeout
import com.github.cloudfiles.onedrive.{OneDriveConfig, OneDriveFileSystem}
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
    val ContentFile = "content.json"
    val TestTimeout = Timeout(11.seconds)
    val spec = new OneDriveProtocolSpec

    spec.createFileSystemFromConfig(DriveID + RootPath + "/" + ContentFile, TestTimeout) match {
      case Success(fs) =>
        fs.contentFile should be(ContentFile)
        fs.rootPath should be(RootPath)
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

  it should "return a failure if the archive URI does not contain a slash" in {
    val ArchiveUri = "invalidOneDriveURI"
    val spec = new OneDriveProtocolSpec

    spec.createFileSystemFromConfig(ArchiveUri, Timeout(1.minute)) match {
      case Failure(exception: IllegalArgumentException) =>
        exception.getMessage should include(ArchiveUri)
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "return a failure if the archive URI ends on a slash" in {
    val ArchiveUri = "driveID/path/to/content/"
    val spec = new OneDriveProtocolSpec

    spec.createFileSystemFromConfig(ArchiveUri, Timeout(1.minute)) match {
      case Failure(exception: IllegalArgumentException) =>
        exception.getMessage should include(ArchiveUri)
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "support an archive URI pointing to a content document in the root" in {
    val DriveID = "theDrive"
    val ContentFile = "toc.json"
    val spec = new OneDriveProtocolSpec

    spec.createFileSystemFromConfig(DriveID + "/" + ContentFile, Timeout(1.hour)) match {
      case Success(fs) =>
        fs.contentFile should be(ContentFile)
        fs.rootPath should be("")
        val onedrive = fs.fileSystem.asInstanceOf[OneDriveFileSystem]
        onedrive.config.optRootPath should be(None)
        onedrive.config.driveID should be(DriveID)
      case r => fail("Unexpected result: " + r)
    }
  }
}
