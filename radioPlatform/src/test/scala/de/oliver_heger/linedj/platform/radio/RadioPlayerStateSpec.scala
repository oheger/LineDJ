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

package de.oliver_heger.linedj.platform.radio

import de.oliver_heger.linedj.player.server.model.RadioModel
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object RadioPlayerStateSpec:
  /**
    * Generates an ID for the test radio source with the given index.
    *
    * @param index the index of the source
    * @return the ID for this test radio source
    */
  private def radioSourceID(index: Int): String = "source-" + index

  /**
    * Returns a test radio source based on a given index.
    *
    * @param index the index of the test radio source
    * @return the test radio source for this index
    */
  private def testRadioSource(index: Int): RadioModel.RadioSource =
    RadioModel.RadioSource(
      id = radioSourceID(index),
      name = s"Test Radio Source $index",
      ranking = index,
      favoriteIndex = index
    )

  /**
    * Creates a [[RadioPlayerState]] object with the given number of test
    * sources, starting with index 1. All other properties have empty default
    * values.
    *
    * @param sourceCount the number of test radio sources
    * @return the test state object
    */
  private def testRadioPlayerState(sourceCount: Int): RadioPlayerState =
    val sources = (1 to sourceCount).map(testRadioSource)
    RadioPlayerState(
      radioSources = sources.toList,
      optCurrentSourceID = None,
      optReplacementID = None,
      optTitle = None,
      playbackEnabled = false
    )
end RadioPlayerStateSpec

/**
  * Test class for [[RadioPlayerState]].
  */
class RadioPlayerStateSpec extends AnyFlatSpec, Matchers, OptionValues:

  import RadioPlayerStateSpec.*

  "A RadioPlayerState" should "return a source for a known source ID" in :
    val state = testRadioPlayerState(4)

    val source = state.sourceByID(radioSourceID(2))

    source.value should be(testRadioSource(2))

  it should "return None for an unknown source ID" in :
    val state = testRadioPlayerState(3)

    val source = state.sourceByID(radioSourceID(4))

    source shouldBe empty

  it should "return an empty option if there is no current source" in :
    val state = testRadioPlayerState(1)

    val currentSource = state.optCurrentSource

    currentSource shouldBe empty

  it should "return a defined option for the current source" in :
    val expectedCurrentSource = testRadioSource(3)
    val state = testRadioPlayerState(8).copy(optCurrentSourceID = Some(expectedCurrentSource.id))

    val currentSource = state.optCurrentSource

    currentSource.value should be(expectedCurrentSource)

  it should "return an empty option if there is no replacement source" in :
    val state = testRadioPlayerState(1)

    val replacementSource = state.optReplacementSource

    replacementSource shouldBe empty

  it should "return a defined option for the replacement source" in :
    val expectedReplacementSource = testRadioSource(5)
    val state = testRadioPlayerState(8).copy(optReplacementID = Some(expectedReplacementSource.id))

    val replacementSource = state.optReplacementSource

    replacementSource.value should be(expectedReplacementSource)
