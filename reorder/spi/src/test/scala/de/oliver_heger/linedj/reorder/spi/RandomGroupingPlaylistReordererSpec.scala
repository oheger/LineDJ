/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.reorder.spi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.annotation.tailrec
import scala.collection.immutable.Seq

object RandomGroupingPlaylistReordererSpec:

  import MetadataExtractorTestHelper.*

  /** A sequence with test songs. */
  private val Songs = createSongs()

  /**
    * Creates a sequence of test songs.
    *
    * @return the test songs
    */
  private def createSongs(): Seq[TestMediaFile] =
    List(
      "Another Brick in the Wall",
      "Pop Goes the World",
      "Almost a Dance",
      "Perfect Ten",
      "Roam If You Want To",
      "After Midnight",
      "Rock me",
      "Reality",
      "Push It"
    ) map : t =>
      createFile(title = Some(t))

  /**
    * Performs some evaluation on the songs produced by the reorder service.
    * The resulting tuple contains a string with the first letters of the
    * song groups, a string with the last encountered song (this is used only
    * temporarily), and a flag whether the order in the groups was correct.
    *
    * @param orderedSongs the ordered songs
    * @return the results of the evaluation
    */
  private def evalSongs(orderedSongs: Seq[TestMediaFile]): (String, String, Boolean) =
    val state = ("_", "", true)
    orderedSongs.foldLeft(state) : (state, song) =>
      val title = TestMetadataExtractor.titleOrDefault(song)
      if title.head != state._1.last then
        (state._1 + title.head, "", state._3)
      else (state._1, title, state._3 && state._2 < title)
end RandomGroupingPlaylistReordererSpec

/**
  * Test class for ''RandomGroupingPlaylistReorderer''.
  */
class RandomGroupingPlaylistReordererSpec extends AnyFlatSpec with Matchers:

  import RandomGroupingPlaylistReordererSpec.*

  import MetadataExtractorTestHelper.*

  /**
    * Creates a test object. This test implementation groups songs by their
    * first letters and then sorts them alphabetically.
    *
    * @return the test object
    */
  private def createReorder(): RandomGroupingPlaylistReorderer[Char] =
    new RandomGroupingPlaylistReorderer[Char]:

      override val resourceBundleBaseName: String = "Irrelevant"

      override def groupSong[A](extractor: MetadataExtractor[A])(song: A): Char =
        extractor.titleOrDefault(song).head

      override def groupOrdering[A](extractor: MetadataExtractor[A]): Ordering[A] =
        Ordering.by(extractor.titleOrDefault)

  /**
    * Reorders the test songs using a test instance and evaluates the outcome.
    *
    * @return a tuple with the evaluation results
    */
  private def reorderAndEvalSongs(): (String, String, Boolean) =
    evalSongs(createReorder().reorder(Songs, TestMetadataExtractor))

  "A RandomGroupingPlaylistReorderer" should "return a seq with the same elements" in :
    createReorder().reorder(Songs, TestMetadataExtractor) should contain theSameElementsAs Songs

  it should "use the correct ordering within the groups" in :
    val (_, _, ordered) = reorderAndEvalSongs()
    ordered shouldBe true

  it should "correctly group songs" in :
    val (groups, _, _) = reorderAndEvalSongs()
    groups should have length 4

  it should "generate a random order" in :
    val reorder = createReorder()
    val (order, _, _) = evalSongs(reorder.reorder(Songs, TestMetadataExtractor))

    @tailrec def go(attempts: Int): Boolean =
      if attempts <= 0 then false
      else
        val (nextOrder, _, _) = evalSongs(reorder.reorder(Songs, TestMetadataExtractor))
        if nextOrder == order then go(attempts - 1) else true

    go(16) shouldBe true
