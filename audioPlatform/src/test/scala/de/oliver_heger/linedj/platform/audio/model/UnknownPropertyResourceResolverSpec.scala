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

package de.oliver_heger.linedj.platform.audio.model

import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import net.sf.jguiraffe.gui.app.ApplicationContext
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

object UnknownPropertyResourceResolverSpec {
  /** Test media ID. */
  private val TestID = MediaFileID(MediumID("someMedium", None), "someSongUri")

  /** Resource ID for an unknown artist. */
  private val ResUnknownArtist = "unknownArtist"

  /** Resource ID for an unknown album. */
  private val ResUnknownAlbum = "unknownAlbum"

  /** The name to be used for an unknown artist. */
  private val UnknownArtistName = "Unknown Artist"

  /** The name to be used for an unknown album. */
  private val UnknownAlbumName = "Whatever Album"
}

/**
  * Test class for ''UnknownPropertyResourceResolver''.
  */
class UnknownPropertyResourceResolverSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  import UnknownPropertyResourceResolverSpec._

  /**
    * Creates a test resolver instance.
    *
    * @param titleProcessors a list with song title processors
    * @return the test instance
    */
  private def createResolver(titleProcessors: List[SongTitleProcessor] = Nil):
  UnknownPropertyResourceResolver = {
    val ctx = mock[ApplicationContext]
    when(ctx.getResourceText(ResUnknownArtist)).thenReturn(UnknownArtistName)
    when(ctx.getResourceText(ResUnknownAlbum)).thenReturn(UnknownAlbumName)
    new UnknownPropertyResourceResolver(ctx, ResUnknownArtist, ResUnknownAlbum, titleProcessors)
  }

  "An UnknownPropertyResourceResolver" should "resolve an unknown artist" in {
    val resolver = createResolver()

    resolver resolveArtistName TestID should be(UnknownArtistName)
  }

  it should "resolve an unknown album" in {
    val resolver = createResolver()

    resolver resolveAlbumName TestID should be(UnknownAlbumName)
  }

  it should "cache the names for unknown properties" in {
    val resolver = createResolver()

    resolver resolveArtistName TestID
    resolver resolveArtistName TestID
    resolver resolveAlbumName TestID
    resolver resolveAlbumName TestID
    verify(resolver.appCtx).getResourceText(ResUnknownArtist)
    verify(resolver.appCtx).getResourceText(ResUnknownAlbum)
  }

  it should "correctly apply song title processors" in {
    val fileName = "mySong"
    val songId = MediaFileID(uri = fileName + ".test", mediumID = null)
    val processors = List(SongTitleExtensionProcessor)
    val resolver = createResolver(titleProcessors = processors)

    resolver resolveTitle songId should be(fileName)
  }

  it should "support the initialization of title processors from a Java collection" in {
    val proc1 = mock[SongTitleProcessor]
    val proc2 = mock[SongTitleProcessor]
    val processors = java.util.Arrays.asList(proc1, proc2)

    val resolver = new UnknownPropertyResourceResolver(mock[ApplicationContext], ResUnknownArtist,
      ResUnknownAlbum, processors)
    resolver.titleProcessors should contain theSameElementsInOrderAs List(proc1, proc2)
  }
}
