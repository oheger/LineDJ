/*
 * Copyright 2015-2023 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.player.engine.radio.config

import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.IntervalQuery
import de.oliver_heger.linedj.player.engine.radio.RadioSource
import de.oliver_heger.linedj.player.engine.radio.control.RadioSourceConfigTestHelper.radioSource
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for [[RadioSourceConfig]].
  */
class RadioSourceConfigSpec extends AnyFlatSpec with Matchers {
  "The Empty RadioSourceConfig" should "contain no named sources" in {
    RadioSourceConfig.Empty.namedSources shouldBe empty
  }

  it should "return an empty sequence of sources" in {
    RadioSourceConfig.Empty.sources shouldBe empty
  }

  it should "return an empty sequence of queries" in {
    val source = RadioSource("testSource")

    RadioSourceConfig.Empty.exclusions(source) shouldBe empty
  }

  it should "return a default ranking for all sources" in {
    val source = RadioSource("anotherTestSource")

    RadioSourceConfig.Empty.ranking(source) should be(RadioSourceConfig.DefaultRanking)
  }

  "The Empty MetadataConfig" should "not contain any exclusions" in {
    MetadataConfig.Empty.exclusions shouldBe empty
  }

  it should "return an empty metadata configuration for sources per default" in {
    val SourcesCount = 16
    (1 to SourcesCount) foreach { idx =>
      val source = radioSource(idx)
      val metadataConfig = MetadataConfig.Empty.metadataSourceConfig(source)
      metadataConfig should be theSameInstanceAs MetadataConfig.EmptySourceConfig
    }

    MetadataConfig.EmptySourceConfig.exclusions shouldBe empty
  }

  "A RadioSourceConfig" should "correctly return sources from named sources" in {
    val SourcesCount = 8
    val testSources = (1 to SourcesCount) map { idx => RadioSource(s"TestSource$idx") }
    val sourceNames = (1 to SourcesCount) map { idx => s"Source$idx" }
    val config = new RadioSourceConfig {
      override def namedSources: Seq[(String, RadioSource)] = sourceNames zip testSources

      override def exclusions(source: RadioSource): Seq[IntervalQuery] = Seq.empty

      override def ranking(source: RadioSource): Int = 0
    }

    val result = config.sources

    result should contain theSameElementsInOrderAs testSources
  }
}
