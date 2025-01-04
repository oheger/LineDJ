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

package de.oliver_heger.linedj.archive.media

import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths

/**
  * Test class for ''PathUriConverter''.
  */
class PathUriConverterSpec extends AnyFlatSpec with Matchers:
  "PathUriConverter" should "convert a path to an URI" in:
    val Root = Paths get "archiveRoot"
    val mediaPath = Root.resolve("rock/the artist/the album/the song.mp3")
    val converter = new PathUriConverter(Root)

    val uri = converter.pathToUri(mediaPath)
    uri should be(MediaFileUri("rock/the%20artist/the%20album/the%20song.mp3"))

  it should "convert a URI to a path" in:
    val Root = Paths.get("archiveRoot", "my media files")
    val uri = MediaFileUri("rock/the%20artist/the%20album/the%20song.mp3")
    val expPath = Paths.get("archiveRoot", "my media files", "rock", "the artist", "the album",
      "the song.mp3")
    val converter = new PathUriConverter(Root)

    val path = converter.uriToPath(uri)
    path should be(expPath)
