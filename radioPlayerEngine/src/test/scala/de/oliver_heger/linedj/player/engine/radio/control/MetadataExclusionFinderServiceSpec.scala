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

package de.oliver_heger.linedj.player.engine.radio.control

import de.oliver_heger.linedj.AsyncTestHelper
import de.oliver_heger.linedj.player.engine.interval.IntervalQueries
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.IntervalQuery
import de.oliver_heger.linedj.player.engine.radio.CurrentMetadata
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.MatchContext.MatchContext
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.ResumeMode.ResumeMode
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.{MatchContext, MetadataExclusion, RadioSourceMetadataConfig, ResumeMode}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.time.{LocalDateTime, Month}
import java.util.regex.Pattern
import scala.collection.immutable.Seq
import scala.concurrent.duration.*

object MetadataExclusionFinderServiceSpec:
  /** The sequence number used by tests. */
  private val SeqNo = 4224

  /** A regular expression pattern to extract artist and song title. */
  private val RegSongData = Pattern.compile(s"(?<${MetadataConfig.ArtistGroup}>[^/]+)/\\s*" +
    s"(?<${MetadataConfig.SongTitleGroup}>.+)")

  /** A reference time used by tests. */
  private val RefTime = LocalDateTime.of(2023, Month.MAY, 9, 21, 36, 38)

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
                              applicable: Seq[IntervalQuery] = Seq.empty,
                              name: Option[String] = None): MetadataConfig.MetadataExclusion =
    MetadataConfig.MetadataExclusion(pattern, matchContext, resumeMode, checkInterval, applicable, name)

/**
  * Test class for [[MetadataExclusionFinderService]] and its default
  * implementation.
  */
class MetadataExclusionFinderServiceSpec extends AnyFlatSpec with Matchers with MockitoSugar with AsyncTestHelper:

  import MetadataExclusionFinderServiceSpec._
  import scala.concurrent.ExecutionContext.Implicits.global

  /**
    * Helper function to invoke the finder service under test with the given
    * parameters.
    *
    * @param config           the metadata config
    * @param sourceConfig     the config for the radio source
    * @param metadata         the metadata to check
    * @param optFinderService allows overriding the finder service
    * @return the result returned from the service
    */
  private def findMetadataExclusion(config: MetadataConfig,
                                    sourceConfig: MetadataConfig.RadioSourceMetadataConfig,
                                    metadata: CurrentMetadata,
                                    optFinderService: Option[MetadataExclusionFinderService] = None):
  Option[MetadataExclusion] =
    val finderService = new MetadataExclusionFinderServiceImpl(EvaluateIntervalsServiceImpl)
    val futResponse = finderService.findMetadataExclusion(config, sourceConfig, metadata, RefTime, SeqNo)
    val response = futureResult(futResponse)

    response.seqNo should be(SeqNo)
    response.result

  "findMetadataExclusion" should "return None if there are no exclusions" in:
    val metadata = CurrentMetadata("some metadata")

    findMetadataExclusion(MetadataConfig.Empty, MetadataConfig.EmptySourceConfig,
      metadata) shouldBe empty

  it should "find an exclusion in the raw metadata" in:
    val metadata = CurrentMetadata("This is a match, yeah!")
    val exclusion = createExclusion()
    val sourceConfig = RadioSourceMetadataConfig(exclusions = Seq(exclusion))

    val result = findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata)

    result should be(Some(exclusion))

  it should "return None if there is no match" in:
    val metadata = CurrentMetadata("Some other metadata")
    val sourceConfig = RadioSourceMetadataConfig(exclusions = Seq(createExclusion()))

    findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata) shouldBe empty

  it should "find an exclusion in the stream title" in:
    val metadata = CurrentMetadata("other;StreamTitle='A match in the title';foo='bar';")
    val exclusion = createExclusion(matchContext = MatchContext.Title)
    val sourceConfig = RadioSourceMetadataConfig(exclusions = Seq(exclusion))

    val result = findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata)

    result should be(Some(exclusion))

  it should "evaluate the stream title context" in:
    val metadata = CurrentMetadata("other='Would be a match';StreamTitle='But not here';")
    val exclusion = createExclusion(matchContext = MatchContext.Title)
    val sourceConfig = RadioSourceMetadataConfig(exclusions = Seq(exclusion))

    findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata) shouldBe empty

  it should "find an exclusion in the artist" in:
    val metadata = CurrentMetadata("StreamTitle='Artist match /song title';")
    val exclusion = createExclusion(matchContext = MatchContext.Artist)
    val sourceConfig = RadioSourceMetadataConfig(optSongPattern = Some(RegSongData), exclusions = Seq(exclusion))

    val result = findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata)

    result should be(Some(exclusion))

  it should "evaluate the artist context" in:
    val metadata = CurrentMetadata("StreamTitle='unknown/song title match';")
    val exclusion = createExclusion(matchContext = MatchContext.Artist)
    val sourceConfig = RadioSourceMetadataConfig(optSongPattern = Some(RegSongData), exclusions = Seq(exclusion))

    findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata) shouldBe empty

  it should "find an exclusion in the song title" in:
    val metadata = CurrentMetadata("StreamTitle='Artist name /matching song title';")
    val exclusion = createExclusion(matchContext = MatchContext.Song)
    val sourceConfig = RadioSourceMetadataConfig(optSongPattern = Some(RegSongData), exclusions = Seq(exclusion))

    val result = findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata)

    result should be(Some(exclusion))

  it should "evaluate the song title context" in:
    val metadata = CurrentMetadata("StreamTitle='artist match/ unknown song title';")
    val exclusion = createExclusion(matchContext = MatchContext.Song)
    val sourceConfig = RadioSourceMetadataConfig(optSongPattern = Some(RegSongData), exclusions = Seq(exclusion))

    findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata) shouldBe empty

  it should "find a match in the artist if no song title pattern is defined for the source" in:
    val metadata = CurrentMetadata("StreamTitle='unknown/song title match';")
    val exclusion = createExclusion(matchContext = MatchContext.Artist)
    val sourceConfig = RadioSourceMetadataConfig(exclusions = Seq(exclusion))

    val result = findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata)

    result should be(Some(exclusion))

  it should "find a match in the song title if no song title pattern is defined for the source" in:
    val metadata = CurrentMetadata("StreamTitle='artist match/unknown song title';")
    val exclusion = createExclusion(matchContext = MatchContext.Song)
    val sourceConfig = RadioSourceMetadataConfig(exclusions = Seq(exclusion))

    val result = findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata)

    result should be(Some(exclusion))

  it should "find a match in global exclusions" in:
    val metadata = CurrentMetadata("StreamTitle='artist/match song';")
    val exclusion = createExclusion(matchContext = MatchContext.Song)
    val metaConfig = mock[MetadataConfig]
    when(metaConfig.exclusions).thenReturn(Seq(exclusion))

    val result = findMetadataExclusion(metaConfig, MetadataConfig.EmptySourceConfig, metadata)

    result should be(Some(exclusion))

  it should "ignore an exclusion that is not applicable" in:
    val applicableAt = List(IntervalQueries.minutes(40, 50))
    val exclusion = createExclusion(matchContext = MatchContext.Artist, applicable = applicableAt)
    val metadata = CurrentMetadata("StreamTitle='Artist match/some song'")
    val sourceConfig = RadioSourceMetadataConfig(exclusions = Seq(exclusion))

    val result = findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata)

    result shouldBe empty

  it should "find the correct exclusion if multiple have time restrictions" in:
    val patternNoMatch = Pattern.compile("foo")
    val exclusionNoMatch = createExclusion(pattern = patternNoMatch)
    val exclusionBefore = createExclusion(matchContext = MatchContext.Song,
      applicable = List(IntervalQueries.minutes(0, 5)))
    val exclusionInside = createExclusion(matchContext = MatchContext.Song,
      applicable = List(IntervalQueries.minutes(30, 40)))
    val exclusionAfter = createExclusion(matchContext = MatchContext.Song,
      applicable = List(IntervalQueries.minutes(40, 50)))
    val metadata = CurrentMetadata("StreamTitle='Some artist/song match'")
    val sourceConfig = RadioSourceMetadataConfig(exclusions = Seq(exclusionBefore, exclusionNoMatch, exclusionInside,
      exclusionAfter))

    val result = findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata)

    result should be(Some(exclusionInside))

  it should "not evaluate interval queries if a match without time restrictions was found" in:
    val exclusionAlways = createExclusion()
    val exclusionPartTime = createExclusion(applicable = List(IntervalQueries.minutes(30, 40)))
    val finderService = mock[MetadataExclusionFinderService]
    when(finderService.findMetadataExclusion(any(), any(), any(), any(), any())(any))
      .thenThrow(new UnsupportedOperationException("Unexpected invocation"))
    val metadata = CurrentMetadata("StreamTitle='Some artist/song match'")
    val sourceConfig = RadioSourceMetadataConfig(exclusions = Seq(exclusionPartTime, exclusionAlways))

    val result = findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata,
      optFinderService = Some(finderService))

    result should be(Some(exclusionAlways))
