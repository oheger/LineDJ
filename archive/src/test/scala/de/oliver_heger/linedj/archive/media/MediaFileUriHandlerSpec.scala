/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.archive.media

import java.nio.file.{Path, Paths}

import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.MediumID
import org.scalatest.{FlatSpec, Matchers}

object MediaFileUriHandlerSpec {
  /** The name of the root path. */
  private val RootName = "mediumRoot"

  /** A root medium path. */
  private val Root = Paths get RootName

  /** A sub directory for song files. */
  private val Directory = "music"

  /** A test medium ID. */
  private val TestMedium = MediumID(RootName, None)

  /**
    * Generates the path to a test file.
    *
    * @param name the name of the file
    * @return the path
    */
  private def filePath(name: String): Path = Paths.get(RootName, Directory, name)

  /**
    * Generates a ''FileData'' object for the specified file name.
    *
    * @param name the name of the file
    * @return a test ''FileData'' for this file
    */
  private def fileData(name: String): FileData = FileData(filePath(name), 42)
}

/**
  * Test class for ''MediaFileUriHandler''.
  */
class MediaFileUriHandlerSpec extends FlatSpec with Matchers {

  import MediaFileUriHandlerSpec._

  "A MediaFileUriHandler" should "generate a correct media file URI" in {
    val handler = new MediaFileUriHandler
    val fileName = "MySong.mp3"

    handler.generateMediaFileUri(Root, filePath(fileName)) should be(s"path://$Directory/$fileName")
  }

  it should "generate a correct URI for the global undefined medium" in {
    val fileURI = s"path://$Directory/someFile.mp3"
    val handler = new MediaFileUriHandler

    handler.generateUndefinedMediumUri(TestMedium, fileURI) should be("ref://" + RootName + ":" + fileURI)
  }

  it should "return None if an unknown medium ID is to be resolved" in {
    val handler = new MediaFileUriHandler

    handler.resolveUri(TestMedium, "path://somePath/someFile.mp3", scala.collection.mutable.Map
      .empty) shouldBe 'empty
  }

  it should "resolve a valid path URI" in {
    val fileName = "CorrectSong.mp3"
    val fileData1 = fileData(fileName)
    val fileData2 = fileData("someOtherFile.ogg")
    val fileUri = s"path://$Directory/$fileName"
    val otherUri = s"path://$Directory/someOtherFile.ogg"
    val UriMapping = Map(fileUri -> fileData1, otherUri -> fileData2)
    val MediaData = scala.collection.mutable.Map(TestMedium -> UriMapping)
    val handler = new MediaFileUriHandler

    handler.resolveUri(TestMedium, fileUri, MediaData) should be(Some(fileData1))
  }

  it should "return None if an unknown URI is to be resolved" in {
    val UriMapping = Map("someUri" -> fileData("someFile"))
    val MediaData = scala.collection.mutable.Map(TestMedium -> UriMapping)
    val handler = new MediaFileUriHandler

    handler.resolveUri(TestMedium, "path://somePath/someFile.mp3", MediaData) shouldBe 'empty
  }

  it should "resolve a valid URI for the global undefined medium" in {
    val fileName = "ReferencedSong.mp3"
    val data = fileData(fileName)
    val fileUri = s"path://$Directory/$fileName"
    val UriMapping = Map(fileUri -> data)
    val MediaData = scala.collection.mutable.Map(TestMedium -> UriMapping)
    val refUri = "ref://" + RootName + ":" + fileUri
    val handler = new MediaFileUriHandler

    handler.resolveUri(MediumID.UndefinedMediumID, refUri, MediaData) should be(Some(data))
  }

  it should "return None for an invalid URI for the global undefined medium" in {
    val refUri = "ref://" + RootName + ":NoPathUriFollows"
    val handler = new MediaFileUriHandler

    handler.resolveUri(MediumID.UndefinedMediumID, refUri,
      scala.collection.mutable.Map.empty) shouldBe 'empty
  }

  it should "return None for a reference URI if the target medium is unknown" in {
    val fileName = "ReferencedSong.mp3"
    val data = fileData(fileName)
    val fileUri = s"path://$Directory/$fileName"
    val UriMapping = Map(fileUri -> data)
    val MediaData = scala.collection.mutable.Map(TestMedium -> UriMapping)
    val refUri = "ref://unknownMedium:" + fileUri
    val handler = new MediaFileUriHandler

    handler.resolveUri(MediumID.UndefinedMediumID, refUri, MediaData) shouldBe 'empty
  }

  it should "be able to remove a path prefix" in {
    val relUri = s"$Directory/someFile.mp3"
    val fileUri = s"path://$relUri"
    val handler = new MediaFileUriHandler

    handler removePrefix fileUri should be(relUri)
  }

  it should "be able to remove a reference prefix" in {
    val relUri = s"someMedium:path://$Directory/someFile.mp3"
    val fileUri = s"ref://$relUri"
    val handler = new MediaFileUriHandler

    handler removePrefix fileUri should be(relUri)
  }

  it should "not change a URI without a prefix" in {
    val uri = "uri:without:prefix"
    val handler = new MediaFileUriHandler

    handler removePrefix uri should be(uri)
  }
}
