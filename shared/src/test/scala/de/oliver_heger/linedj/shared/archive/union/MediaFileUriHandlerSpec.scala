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

package de.oliver_heger.linedj.shared.archive.union

import java.nio.file.{Path, Paths}

import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.MediumID
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object MediaFileUriHandlerSpec {
  /** The name of the root path. */
  private val RootName = "mediumRoot"

  /** The name of an archive component ID. */
  private val ComponentID = "Archive Component ID:42"

  /** Constant for an URL encoded component ID. */
  private val ComponentIDEnc = "Archive%20Component%20ID%3A42"

  /** A root medium path. */
  private val Root = Paths get RootName

  /** A sub directory for song files. */
  private val Directory = "my music"

  /** The encoded name of the sub directory with song files. */
  private val DirectoryEnc = "my%20music"

  /** A test medium ID. */
  private val TestMedium = MediumID(RootName, None, ComponentID)

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
  private def fileData(name: String): FileData = FileData(filePath(name).toString, 42)

  /**
    * Generates an encoded URI for the given file name by prepending the
    * encoded test directory name. (The file name is not encoded.)
    *
    * @param fileName the file name
    * @return the encoded URI for this file
    */
  private def fileUriEnc(fileName: String): String = s"$DirectoryEnc/$fileName"
}

/**
  * Test class for ''MediaFileUriHandler''.
  */
class MediaFileUriHandlerSpec extends AnyFlatSpec with Matchers {

  import MediaFileUriHandlerSpec._

  "A MediaFileUriHandler" should "generate a correct media file URI" in {
    val fileName = "MySong.mp3"

    MediaFileUriHandler.generateMediaFileUri(Root,
      filePath(fileName)) should be(fileUriEnc(fileName))
  }

  it should "generate a correct URI for the global undefined medium" in {
    val fileName = "someFile.mp3"
    val fileURI = fileUriEnc(fileName)

    val expected = "ref://" + RootName + ":" + ComponentIDEnc + ":" +
      MediaFileUriHandler.PrefixPath + fileURI
    MediaFileUriHandler.generateUndefinedMediumUri(TestMedium, fileURI) should be(expected)
  }

  it should "generate a correct undefined medium URI if the path prefix is already there" in {
    val fileURI = s"path://$DirectoryEnc/someFile.mp3"

    val expected = "ref://" + RootName + ":" + ComponentIDEnc + ":" + fileURI
    MediaFileUriHandler.generateUndefinedMediumUri(TestMedium, fileURI) should be(expected)
  }

  it should "return None if an unknown medium ID is to be resolved" in {
    MediaFileUriHandler.resolveUri(TestMedium, "path://somePath/someFile.mp3",
      Map.empty) shouldBe empty
  }

  it should "resolve a valid path URI" in {
    val fileName = "CorrectSong.mp3"
    val fileData1 = fileData(fileName)
    val fileData2 = fileData("someOtherFile.ogg")
    val uri = fileUriEnc(fileName)
    val otherUri = fileUriEnc("someOtherFile.ogg")
    val UriMapping = Map(uri -> fileData1, otherUri -> fileData2)
    val MediaData = Map(TestMedium -> UriMapping)

    MediaFileUriHandler.resolveUri(TestMedium, fileUriEnc(fileName),
      MediaData) should be(Some(fileData1))
  }

  it should "return None if an unknown URI is to be resolved" in {
    val UriMapping = Map("someUri" -> fileData("someFile"))
    val MediaData = Map(TestMedium -> UriMapping)

    MediaFileUriHandler.resolveUri(TestMedium, "somePath/someFile.mp3",
      MediaData) shouldBe empty
  }

  it should "be able to split a valid URI for the global undefined medium" in {
    val fileName = "Music.mp3"
    val filePath = fileUriEnc(fileName)
    val pathUri = MediaFileUriHandler.PrefixPath + filePath
    val refUri = "ref://" + RootName + ":" + ComponentIDEnc + ":" + pathUri

    val uri = MediaFileUriHandler extractRefUri refUri
    uri.get should be(UndefinedMediumUri(RootName, ComponentID, filePath))
  }

  it should "resolve a valid URI for the global undefined medium" in {
    val fileName = "ReferencedSong.mp3"
    val data = fileData(fileName)
    val pathUri = fileUriEnc(fileName)
    val UriMapping = Map(pathUri -> data)
    val MediaData = Map(TestMedium -> UriMapping)
    val refUri = "ref://" + RootName + ":" + ComponentIDEnc + ":" +
      MediaFileUriHandler.PrefixPath + pathUri

    MediaFileUriHandler.resolveUri(MediumID.UndefinedMediumID, refUri,
      MediaData) should be(Some(data))
  }

  it should "resolve an undefined medium URI to the correct archive component" in {
    val fileName = "ReferencedSong.mp3"
    val data = fileData(fileName)
    val pathUri = fileUriEnc(fileName)
    val mid2 = MediumID(RootName, None, ComponentID + "_other")
    val UriMapping = Map(pathUri -> data)
    val UriMapping2 = Map(pathUri -> fileData("anotherName.mp3"))
    val MediaData = Map(mid2 -> UriMapping2, TestMedium -> UriMapping)
    val refUri = "ref://" + RootName + ":" + ComponentIDEnc + ":" +
      MediaFileUriHandler.PrefixPath + pathUri

    MediaFileUriHandler.resolveUri(MediumID.UndefinedMediumID, refUri,
      MediaData) should be(Some(data))
  }

  it should "return None for an invalid URI for the global undefined medium" in {
    val refUri = "ref://" + RootName + ":" + ComponentIDEnc + ":NoPathUriFollows"

    MediaFileUriHandler.resolveUri(MediumID.UndefinedMediumID, refUri,
      Map.empty) shouldBe empty
  }

  it should "return None for a reference URI if the target medium is unknown" in {
    val fileName = "ReferencedSong.mp3"
    val data = fileData(fileName)
    val pathUri = fileUriEnc(fileName)
    val UriMapping = Map(pathUri -> data)
    val MediaData = Map(TestMedium -> UriMapping)
    val refUri = "ref://unknownMedium:" + ComponentIDEnc + ":" + MediaFileUriHandler.PrefixPath +
      pathUri

    MediaFileUriHandler.resolveUri(MediumID.UndefinedMediumID, refUri,
      MediaData) shouldBe empty
  }

  it should "be able to remove a path prefix" in {
    val relUri = s"$Directory/someFile.mp3"
    val fileUri = s"path://$relUri"

    MediaFileUriHandler removePrefix fileUri should be(relUri)
  }

  it should "be able to remove a reference prefix" in {
    val relUri = s"someMedium:someComponent:path://$Directory/someFile.mp3"
    val fileUri = s"ref://$relUri"

    MediaFileUriHandler removePrefix fileUri should be(relUri)
  }

  it should "not change a URI without a prefix" in {
    val uri = "uri:without:prefix"

    MediaFileUriHandler removePrefix uri should be(uri)
  }

  it should "find a specific undefined medium ID" in {
    val refUri = "ref://" + RootName + ":" + ComponentIDEnc + ":" +
      MediaFileUriHandler.PrefixPath + "somePath/someSong.mp3"
    val media = Seq(MediumID(RootName, None), MediumID("otherRoot", None, ComponentID),
      MediumID("foo", Some("bar"), "someComp"), TestMedium)

    MediaFileUriHandler.findSpecificUndefinedMedium(refUri, media).get should be(TestMedium)
  }

  it should "return None as specific medium for an incorrect URI" in {
    val media = Seq(TestMedium)

    MediaFileUriHandler.findSpecificUndefinedMedium("invalidUri", media) shouldBe empty
  }

  it should "return None as specific medium if no medium can be found" in {
    val refUri = "ref://" + RootName + ":" + ComponentIDEnc + ":" +
      MediaFileUriHandler.PrefixPath + "somePath/someSong.mp3"
    val media = Seq(MediumID(RootName, None), MediumID("otherRoot", None, ComponentID),
      MediumID("foo", Some("bar"), "someComp"))

    MediaFileUriHandler.findSpecificUndefinedMedium(refUri, media) shouldBe empty
  }
}
