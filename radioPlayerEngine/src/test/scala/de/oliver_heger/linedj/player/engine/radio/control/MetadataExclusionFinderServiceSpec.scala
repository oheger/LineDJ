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

package de.oliver_heger.linedj.player.engine.radio.control

import de.oliver_heger.linedj.player.engine.radio.CurrentMetadata
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.MatchContext.MatchContext
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.ResumeMode.ResumeMode
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.{MatchContext, RadioSourceMetadataConfig, ResumeMode}
import de.oliver_heger.linedj.player.engine.radio.control.MetadataExclusionFinderServiceImpl.findMetadataExclusion
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.regex.Pattern
import scala.concurrent.duration._

object MetadataExclusionFinderServiceSpec {
  /** A regular expression pattern to extract artist and song title. */
  private val RegSongData = Pattern.compile(s"(?<${MetadataConfig.ArtistGroup}>[^/]+)/\\s*" +
    s"(?<${MetadataConfig.SongTitleGroup}>.+)")

  /**
    * Convenience function to create a metadata exclusion with default values.
    *
    * @param pattern       the pattern
    * @param matchContext  the match context
    * @param resumeMode    the resume mode
    * @param checkInterval the check interval
    * @param name          the optional name
    * @return the exclusion instance
    */
  private def createExclusion(pattern: Pattern = Pattern.compile(".*match.*"),
                              matchContext: MatchContext = MatchContext.Raw,
                              resumeMode: ResumeMode = ResumeMode.MetadataChange,
                              checkInterval: FiniteDuration = 2.minutes,
                              name: Option[String] = None): MetadataConfig.MetadataExclusion =
    MetadataConfig.MetadataExclusion(pattern, matchContext, resumeMode, checkInterval, name)

}

/**
  * Test class for [[MetadataExclusionFinderService]] and its default
  * implementation.
  */
class MetadataExclusionFinderServiceSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  import MetadataExclusionFinderServiceSpec._

  "findMetadataExclusion" should "return None if there are no exclusions" in {
    val metadata = CurrentMetadata("some metadata")

    findMetadataExclusion(MetadataConfig.Empty, MetadataConfig.EmptySourceConfig,
      metadata) shouldBe empty
  }

  it should "find an exclusion in the raw metadata" in {
    val metadata = CurrentMetadata("This is a match, yeah!")
    val exclusion = createExclusion()
    val sourceConfig = RadioSourceMetadataConfig(exclusions = Seq(exclusion))

    val result = findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata)

    result should be(Some(exclusion))
  }

  it should "return None if there is no match" in {
    val metadata = CurrentMetadata("Some other metadata")
    val sourceConfig = RadioSourceMetadataConfig(exclusions = Seq(createExclusion()))

    findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata) shouldBe empty
  }

  it should "find an exclusion in the stream title" in {
    val metadata = CurrentMetadata("other;StreamTitle='A match in the title';foo='bar';")
    val exclusion = createExclusion(matchContext = MatchContext.Title)
    val sourceConfig = RadioSourceMetadataConfig(exclusions = Seq(exclusion))

    val result = findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata)

    result should be(Some(exclusion))
  }

  it should "evaluate the stream title context" in {
    val metadata = CurrentMetadata("other='Would be a match';StreamTitle='But not here';")
    val exclusion = createExclusion(matchContext = MatchContext.Title)
    val sourceConfig = RadioSourceMetadataConfig(exclusions = Seq(exclusion))

    findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata) shouldBe empty
  }

  it should "find an exclusion in the artist" in {
    val metadata = CurrentMetadata("StreamTitle='Artist match /song title';")
    val exclusion = createExclusion(matchContext = MatchContext.Artist)
    val sourceConfig = RadioSourceMetadataConfig(optSongPattern = Some(RegSongData), exclusions = Seq(exclusion))

    val result = findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata)

    result should be(Some(exclusion))
  }

  it should "evaluate the artist context" in {
    val metadata = CurrentMetadata("StreamTitle='unknown/song title match';")
    val exclusion = createExclusion(matchContext = MatchContext.Artist)
    val sourceConfig = RadioSourceMetadataConfig(optSongPattern = Some(RegSongData), exclusions = Seq(exclusion))

    findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata) shouldBe empty
  }

  it should "find an exclusion in the song title" in {
    val metadata = CurrentMetadata("StreamTitle='Artist name /matching song title';")
    val exclusion = createExclusion(matchContext = MatchContext.Song)
    val sourceConfig = RadioSourceMetadataConfig(optSongPattern = Some(RegSongData), exclusions = Seq(exclusion))

    val result = findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata)

    result should be(Some(exclusion))
  }

  it should "evaluate the song title context" in {
    val metadata = CurrentMetadata("StreamTitle='artist match/ unknown song title';")
    val exclusion = createExclusion(matchContext = MatchContext.Song)
    val sourceConfig = RadioSourceMetadataConfig(optSongPattern = Some(RegSongData), exclusions = Seq(exclusion))

    findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata) shouldBe empty
  }

  it should "find a match in the artist if no song title pattern is defined for the source" in {
    val metadata = CurrentMetadata("StreamTitle='unknown/song title match';")
    val exclusion = createExclusion(matchContext = MatchContext.Artist)
    val sourceConfig = RadioSourceMetadataConfig(exclusions = Seq(exclusion))

    val result = findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata)

    result should be(Some(exclusion))
  }

  it should "find a match in the song title if no song title pattern is defined for the source" in {
    val metadata = CurrentMetadata("StreamTitle='artist match/unknown song title';")
    val exclusion = createExclusion(matchContext = MatchContext.Song)
    val sourceConfig = RadioSourceMetadataConfig(exclusions = Seq(exclusion))

    val result = findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata)

    result should be(Some(exclusion))
  }

  it should "find a match in global exclusions" in {
    val metadata = CurrentMetadata("StreamTitle='artist/match song';")
    val exclusion = createExclusion(matchContext = MatchContext.Song)
    val metaConfig = mock[MetadataConfig]
    when(metaConfig.exclusions).thenReturn(Seq(exclusion))

    val result = findMetadataExclusion(metaConfig, MetadataConfig.EmptySourceConfig, metadata)

    result should be(Some(exclusion))
  }

}
