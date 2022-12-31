/*
 * Copyright 2015-2022 The Developers Team.
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

package de.oliver_heger.linedj.radio

import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import de.oliver_heger.linedj.player.engine.radio.{RadioSource, RadioSourceErrorEvent}
import org.mockito.ArgumentMatchers.{any, anyBoolean}
import org.mockito.Mockito._
import org.mockito.{ArgumentCaptor, Mockito}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.time.LocalDateTime
import scala.concurrent.duration._

object ErrorHandlingStrategySpec {
  /** The minimum retry interval (in milliseconds). */
  private val RetryInterval = 1000

  /** The factor to increase the retry interval. */
  private val RetryIncrement = 1.5

  /** The maximum number of retries. */
  private val MaxRetry = 5

  /** The number of radio sources. */
  private val SourceCount = 8

  /**
    * Constant for a retry interval that is larger than the maximum retry
    * time. This value should cause the current source to be marked as
    * dysfunctional.
    */
  private val ExceededRetryTime = math.round(math.pow(RetryIncrement,
    MaxRetry + 1) * RetryInterval)

  /** The config for the handling strategy. */
  private val StrategyConfig = RadioPlayerConfig(errorConfig = createErrorConfig(),
    sourceConfig = createSourceConfig(),
    initialDelay = 0,
    metaMaxLen = 70,
    metaRotateScale = 0.9)

  /** The default current source played by the player. */
  private val CurrentSource = radioSource(1)

  /**
    * Produces a test radio source.
    *
    * @param idx the index of the source
    * @return the test radio source
    */
  private def radioSource(idx: Int): RadioSource =
    RadioSource("source" + idx)

  /**
    * Creates an error event for the radio source with the given index.
    *
    * @param idx the index
    * @return the error event
    */
  private def errorEvent(idx: Int): RadioSourceErrorEvent =
    RadioSourceErrorEvent(radioSource(idx),
      time = LocalDateTime.now().withNano(0))

  /**
    * Creates a configuration with the retry settings.
    *
    * @return the cerror onfiguration
    */
  private def createErrorConfig(): ErrorHandlingConfig =
    ErrorHandlingConfig(retryInterval = RetryInterval.millis,
      retryIncrementFactor = RetryIncrement,
      maxRetries = MaxRetry,
      recoveryTime = 1000,
      recoverMinFailedSources = 1)

  /**
    * Creates a mock configuration for radio sources. This object only
    * supports the functionality required by the error handling strategy.
    *
    * @return the mock sources configuration
    */
  private def createSourceConfig(): RadioSourceConfig = {
    val config = Mockito.mock(classOf[RadioSourceConfig])
    val sources = (1 to SourceCount) map { i =>
      val src = radioSource(i)
      when(config.ranking(src)).thenReturn(SourceCount - i)
      (src.uri, src)
    }
    when(config.sources).thenReturn(sources)
    config
  }

  /**
    * Adds a ranking function to the source configuration. The first half of
    * the sources has ranking 1, the other half has ranking 0.
    *
    * @param srcConfig the sources configuration
    * @return the modified sources configuration
    */
  private def addRanking(srcConfig: RadioSourceConfig): RadioSourceConfig = {
    (1 to SourceCount) foreach { i =>
      val rankValue = if (i <= SourceCount / 2) 1 else 0
      when(srcConfig.ranking(radioSource(i))).thenReturn(rankValue)
    }
    srcConfig
  }
}

/**
  * Test class for ''ErrorHandlingStrategy''.
  */
class ErrorHandlingStrategySpec extends AnyFlatSpec with Matchers with MockitoSugar {

  import ErrorHandlingStrategySpec._

  /**
    * Invokes the player action on a radio player mock. The mock can then be
    * used for verification.
    *
    * @param action the player action
    * @return the mock radio player
    */
  private def checkPlayerAction(action: ErrorHandlingStrategy.PlayerAction): RadioPlayer = {
    val player = mock[RadioPlayer]
    action(player)
    player
  }

  "An ErrorHandlingStrategy" should "handle an error for a replacement source" in {
    val strategy = new ErrorHandlingStrategy

    val (action, _) = strategy.handleError(StrategyConfig, ErrorHandlingStrategy.NoError,
      errorEvent(2), CurrentSource)
    verify(checkPlayerAction(action)).checkCurrentSource(Set(radioSource(2)), RetryInterval.millis)
  }

  it should "keep track about failed replacement sources" in {
    val strategy = new ErrorHandlingStrategy
    val (_, next) = strategy.handleError(StrategyConfig, ErrorHandlingStrategy.NoError,
      errorEvent(2), CurrentSource)

    val (action, _) = strategy.handleError(StrategyConfig, next, errorEvent(3),
      CurrentSource)
    verify(checkPlayerAction(action)).checkCurrentSource(Set(radioSource(2), radioSource(3)),
      RetryInterval.millis)
  }

  it should "retry a failing current source" in {
    val strategy = new ErrorHandlingStrategy

    val (action, _) = strategy.handleError(StrategyConfig, ErrorHandlingStrategy.NoError,
      errorEvent(1), CurrentSource)
    verify(checkPlayerAction(action)).playSource(CurrentSource, makeCurrent = true)
  }

  it should "increase the retry interval using the configured factor" in {
    val strategy = new ErrorHandlingStrategy
    val (_, next) = strategy.handleError(StrategyConfig, ErrorHandlingStrategy.NoError,
      errorEvent(1), CurrentSource)

    val (action, _) = strategy.handleError(StrategyConfig, next, errorEvent(1), CurrentSource)
    val nextRetry = math.round(RetryInterval * RetryIncrement).millis
    verify(checkPlayerAction(action)).playSource(CurrentSource, makeCurrent = true, delay = nextRetry)
  }

  it should "correctly calculate the maximum retry time" in {
    val strategy = new ErrorHandlingStrategy
    // math.pow(RetryIncrement, MaxRetry) * RetryInterval, but rounded each step
    val maxRetryTime = 7595L
    val state = ErrorHandlingStrategy.NoError.copy(retryMillis = maxRetryTime)

    val (action, next) = strategy.handleError(StrategyConfig, state,
      errorEvent(1), CurrentSource)
    verify(checkPlayerAction(action)).playSource(CurrentSource, makeCurrent = true, delay = maxRetryTime.millis)
    next.retryMillis should be > maxRetryTime
  }

  it should "switch to another source if the maximum number of retries is reached" in {
    val strategy = new ErrorHandlingStrategy
    val state = ErrorHandlingStrategy.NoError.copy(retryMillis = ExceededRetryTime)

    val (action, next) = strategy.handleError(StrategyConfig, state, errorEvent(1),
      CurrentSource)
    val player = checkPlayerAction(action)
    verify(player).playSource(radioSource(2), makeCurrent = true, delay = RetryInterval.millis)
    next.retryMillis should be(0)
  }

  it should "add a dysfunctional source to the exclusions for replacement sources" in {
    val strategy = new ErrorHandlingStrategy
    val state = ErrorHandlingStrategy.NoError.copy(retryMillis = ExceededRetryTime)
    val (_, next) = strategy.handleError(StrategyConfig, state, errorEvent(1),
      CurrentSource)

    val errEvent = errorEvent(3)
    val (action, _) = strategy.handleError(StrategyConfig, next, errEvent,
      radioSource(2))
    verify(checkPlayerAction(action)).checkCurrentSource(Set(CurrentSource, errEvent.source),
      RetryInterval.millis)
  }

  it should "record the source that was switched to" in {
    val strategy = new ErrorHandlingStrategy
    val state = ErrorHandlingStrategy.NoError.copy(retryMillis = ExceededRetryTime)
    val (_, next) = strategy.handleError(StrategyConfig, state, errorEvent(1),
      CurrentSource)

    val (action, _) = strategy.handleError(StrategyConfig, next, errorEvent(2),
      CurrentSource)
    verify(checkPlayerAction(action))
      .playSource(radioSource(2), makeCurrent = true, delay = RetryInterval.millis)
  }

  it should "mark the next source as dysfunctional correctly" in {
    val strategy = new ErrorHandlingStrategy
    val state = ErrorHandlingStrategy.NoError.copy(retryMillis = ExceededRetryTime)
    val (_, next) = strategy.handleError(StrategyConfig, state, errorEvent(1),
      CurrentSource)

    val (action, _) = strategy.handleError(StrategyConfig,
      next.copy(retryMillis = ExceededRetryTime), errorEvent(2), CurrentSource)
    val player = checkPlayerAction(action)
    verify(player).playSource(radioSource(3), makeCurrent = true, delay = RetryInterval.millis)
  }

  it should "handle the case that all sources are dysfunctional" in {
    val errEvent = errorEvent(SourceCount)
    val state = ErrorHandlingStrategy.NoError.copy(retryMillis = ExceededRetryTime,
      errorList = StrategyConfig.sourceConfig.sources.map(_._2).toSet,
      activeSource = Some(errEvent.source))
    val strategy = new ErrorHandlingStrategy

    val (action, next) = strategy.handleError(StrategyConfig, state, errEvent,
      CurrentSource)
    verify(checkPlayerAction(action)).playSource(CurrentSource, makeCurrent = true,
      delay = StrategyConfig.errorConfig.maxRetryInterval.millis)
    next.activeSource.get should be(CurrentSource)
  }

  it should "select different replacement sources per ranking" in {
    val errorSource = radioSource(2)
    val strategy = new ErrorHandlingStrategy
    val state = ErrorHandlingStrategy.NoError.copy(retryMillis = ExceededRetryTime)
    val rankingStrategyConfig = StrategyConfig.copy(sourceConfig = addRanking(StrategyConfig.sourceConfig))

    // Generates an error event with a deterministic time
    def timedErrorEvent(idx: Int): RadioSourceErrorEvent =
      RadioSourceErrorEvent(errorSource, LocalDateTime.now().withNano(idx * 1000))

    // Fetches the replacement source from the action
    def replacementSource(action: ErrorHandlingStrategy.PlayerAction): RadioSource = {
      val captor = ArgumentCaptor.forClass(classOf[RadioSource])
      verify(checkPlayerAction(action)).playSource(captor.capture(), anyBoolean(), anyBoolean(), any())
      captor.getValue
    }

    val sources = (1 to SourceCount).foldLeft(Set.empty[RadioSource]) { (s, i) =>
      val (action, _) = strategy.handleError(rankingStrategyConfig, state, timedErrorEvent(i),
        errorSource)
      s + replacementSource(action)
    }
    val expected = (1 to (SourceCount / 2)).filterNot(_ == 2) map radioSource
    sources should be(expected.toSet)
  }

  "An ErrorHandlingStrategyState" should "return the number of dysfunctional sources" in {
    ErrorHandlingStrategy.NoError.numberOfErrorSources should be(0)

    val errList = Set(radioSource(2), radioSource(4), radioSource(8))
    val errState = ErrorHandlingStrategy.NoError.copy(errorList = errList)
    errState.numberOfErrorSources should be(3)
  }

  it should "check for an alternative source if there is none" in {
    ErrorHandlingStrategy.NoError
      .isAlternativeSourcePlaying(CurrentSource) shouldBe false
  }

  it should "check for an alternative source if there is one" in {
    val errState = ErrorHandlingStrategy.NoError.copy(activeSource = Some(radioSource(2)))

    errState isAlternativeSourcePlaying CurrentSource shouldBe true
  }

  it should "check for an alternative source if the alternative is current" in {
    val errState = ErrorHandlingStrategy.NoError.copy(activeSource = Some(radioSource(1)))

    errState isAlternativeSourcePlaying CurrentSource shouldBe false
  }
}
