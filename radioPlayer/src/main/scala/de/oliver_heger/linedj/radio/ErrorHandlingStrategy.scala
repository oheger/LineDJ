/*
 * Copyright 2015-2020 The Developers Team.
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

import de.oliver_heger.linedj.player.engine.facade.RadioPlayer
import de.oliver_heger.linedj.player.engine.{RadioSource, RadioSourceErrorEvent}
import org.apache.commons.configuration.Configuration

import scala.annotation.tailrec
import scala.concurrent.duration._

object ErrorHandlingStrategy {
  /**
    * Type for an action that manipulates the radio player. This action has to
    * be executed in reaction of an error.
    */
  type PlayerAction = RadioPlayer => Unit

  /**
    * A special state indicating that no error occurred. This should be used
    * as initial state.
    */
  val NoError = State(replacementBlacklist = Set.empty, retryMillis = 0,
    blacklist = Set.empty, activeSource = None)

  /**
    * The default value for the ''retryInterval'' configuration property (in
    * milliseconds).
    */
  val DefaultRetryInterval = 1000

  /** Minimum value for the retry interval (in milliseconds). */
  val MinimumRetryInterval = 10

  /**
    * The default value for the ''retryIncrement'' configuration property. This
    * value causes a pretty fast increment of retry intervals.
    */
  val DefaultRetryIncrement = 2.0

  /** Minimum retry increment factor. */
  val MinimumRetryIncrement = 1.1

  /**
    * The default value for the ''maxRetries'' configuration property.
    */
  val DefaultMaxRetries = 5

  /**
    * The common prefix for all configuration keys.
    */
  private val KeyPrefix = "radio.error."

  /** Configuration key for the (minimum) retry interval. */
  private val KeyInterval = KeyPrefix + "retryInterval"

  /** Configuration key for the retry interval increment factor. */
  private val KeyIncrement = KeyPrefix + "retryIncrement"

  /** Configuration key for the maximum number of retries for a failing source. */
  private val KeyMaxRetries = KeyPrefix + "maxRetries"

  /**
    * A trait defining the configuration options used by
    * [[ErrorHandlingStrategy]].
    *
    * An object implementing this trait has to be passed to the strategy.
    * Instances can be created using the ''createConfig()'' method of the
    * companion object.
    */
  trait Config {
    /**
      * The retry interval in milliseconds. When an error occurs the strategy
      * will pause at least for this interval and then retry. For multiple
      * errors in series, the interval can be increased with the
      * ''retryIncrementFactor''.
      */
    val retryInterval: FiniteDuration

    /**
      * A factor for incrementing the retry interval if there are multiple
      * errors from the same radio source. This typically indicates a more
      * permanent problem with this source. Therefore, it does not make sense
      * to retry it directly, but make the pauses between two attempts longer
      * and longer, until a configurable maximum is reached. This factor must
      * be greater than 1.
      */
    val retryIncrementFactor: Double

    /**
      * Defines the maximum number of retries for a failing radio source
      * before the player switches to another source. Note that this
      * setting also determines the maximum interval between two retries.
      */
    val maxRetries: Int

    /**
      * The maximum retry interval based on the minimum interval and the
      * maximum number of retries.
      */
    lazy val maxRetryInterval: Long = calcMaxRetry(maxRetries, retryInterval.toMillis)

    /**
      * Returns the configuration for the available radio sources.
      */
    val sourcesConfig: RadioSourceConfig

    /**
      * Calculates the maximum retry interval based on the allowed number of
      * retries.
      *
      * @param count the current counter
      * @return the maximum retry interval
      */
    @tailrec private def calcMaxRetry(count: Int, value: Long): Long =
    if (count == 0) value
    else calcMaxRetry(count - 1, math.round(retryIncrementFactor * value))
  }

  /**
    * A class representing the current error state.
    *
    * The content of this class is evaluated mostly internally by the error
    * handling strategy. Client code should not rely on it and just pass the
    * previous state to the strategy to receive an updated state.
    *
    * There are some properties, however, that can be queried by clients to
    * learn more about the current error state. This is stated explicitly in
    * the documentation.
    *
    * @param blacklist            a set with blacklisted sources
    * @param replacementBlacklist set of blacklisted replacement sources
    * @param retryMillis          the next retry interval in millis
    * @param activeSource         the source currently playing; this is not
    *                             necessarily the current source of the controller
    */
  case class State(blacklist: Set[RadioSource], replacementBlacklist: Set[RadioSource],
                   retryMillis: Long, activeSource: Option[RadioSource]) {
    /**
      * Returns the number of sources which are currently blacklisted. This may
      * be an indicator whether there is a problem with the network connection
      * (if many sources are blacklisted) or if only specific sources have
      * problems.
      *
      * @return the number of blacklisted sources
      */
    def numberOfBlacklistedSources: Int = blacklist.size

    /**
      * Checks whether an alternative source to the specified one is currently
      * playing. This is the case if the current source has been blacklisted,
      * and therefore, another source was selected. This method can be used by
      * the controller to find out whether radio playback is in error mode.
      *
      * @param current the current source (as expected by the controller)
      * @return '''true''' if currently an alternative source is played;
      *         '''false''' otherwise
      */
    def isAlternativeSourcePlaying(current: RadioSource): Boolean =
    activeSource.exists(_ != current)
  }

  /**
    * Creates a ''Config'' object for the error handling strategy. The required
    * settings are read from the ''Configuration'' object. Information about
    * the radio sources available is required, too.
    *
    * @param playerConfig the configuration of the player application
    * @param sourceConfig the configuration for the radio sources
    * @return the configuration for the error handling strategy
    */
  def createConfig(playerConfig: Configuration, sourceConfig: RadioSourceConfig): Config = {
    val interval = math.max(MinimumRetryInterval,
      playerConfig.getInt(KeyInterval, DefaultRetryInterval)).millis
    val increment = math.max(MinimumRetryIncrement,
      playerConfig.getDouble(KeyIncrement, DefaultRetryIncrement))
    val maxRetryCount = playerConfig.getInt(KeyMaxRetries, DefaultMaxRetries)
    new Config {
      override val retryInterval = interval
      override val retryIncrementFactor = increment
      override val maxRetries = maxRetryCount
      override val sourcesConfig = sourceConfig
    }
  }

  /**
    * Handles an error while playing the current radio source.
    *
    * @param config     the configuration settings for the strategy
    * @param previous   the previous error state
    * @param error      the current error event
    * @param ctrlSource the current source provided by the controller
    * @return an action to update the player and the follow-up error state
    */
  private def handleErrorCurrent(config: Config, previous: State, error: RadioSourceErrorEvent,
                                 ctrlSource: RadioSource): (PlayerAction, State) = {
    val currentSource = previous.activeSource getOrElse ctrlSource
    val retry = math.max(previous.retryMillis, config.retryInterval.toMillis)
    if (retry > config.maxRetryInterval) {
      val nextBlacklist = previous.blacklist + currentSource
      selectReplacementSource(config, nextBlacklist, error) match {
        case Some(src) =>
          val action = switchSourceAction(src, config.retryInterval)
          (action, previous.copy(retryMillis = 0, blacklist = nextBlacklist,
            activeSource = Some(src)))

        case None =>
          (switchSourceAction(ctrlSource, config.maxRetryInterval.millis),
            previous.copy(activeSource = Some(ctrlSource)))
      }
    } else {

      val action = switchSourceAction(currentSource, retry.millis)
      (action, previous.copy(retryMillis = math.round(retry * config.retryIncrementFactor)))
    }
  }

  /**
    * Handles an error while playing a replacement source.
    *
    * @param config   the configuration setting for the strategy
    * @param previous the previous error state
    * @param error    the current error event
    * @return an action to update the player and the follow-up error state
    */
  private def handleErrorReplacement(config: Config, previous: State, error: RadioSourceErrorEvent):
  (PlayerAction, State) = {
    val failedSources = previous.replacementBlacklist + error.source
    val action: PlayerAction = p => p.checkCurrentSource(failedSources ++ previous.blacklist,
      config.retryInterval)
    (action, previous.copy(replacementBlacklist = failedSources))
  }

  /**
    * Selects a replacement source from the sources configuration using the
    * specified blacklist. This method should produce some variety: It does
    * not simply return the next highest ranked source from the list of sources
    * because (as the sources are sorted alphabetically) this would yield a
    * deterministic sequence. Rather, all sources with the next highest
    * ranking are determined, and the time of the event is used as random
    * source to select one of them. If there are no more sources left to
    * choose from, result is ''None''.
    *
    * @param config    the configuration settings for the strategy
    * @param blacklist the set of sources to be excluded
    * @param error     the current error event
    * @return an option with the selected source
    */
  private def selectReplacementSource(config: Config, blacklist: Set[RadioSource],
                                      error: RadioSourceErrorEvent): Option[RadioSource] = {
    def blacklisted(e: (String, RadioSource)): Boolean =
      blacklist contains e._2

    val nextCandidates = config.sourcesConfig.sources dropWhile blacklisted
    val nextSource = nextCandidates.headOption map { e =>
      val ranking = config.sourcesConfig.ranking(e._2)
      val rankedCandidates = nextCandidates.takeWhile(t =>
        config.sourcesConfig.ranking(t._2) == ranking) filterNot blacklisted
      val idx = (error.time.getNano / 1000) % (rankedCandidates.size + 1)
      (e :: rankedCandidates.toList).drop(idx).head._2
    }
    nextSource
  }

  /**
    * Tests whether the source affected by the error event is the one which is
    * currently played. (This is either the current source of the radio
    * controller or the one this strategy switched to.) Otherwise, the error
    * refers to a replacement source.
    *
    * @param state         the error state
    * @param error         the error event
    * @param currentSource the current source of the radio controller
    * @return a flag whether the error affects the active source
    */
  private def isActiveSource(state: State, error: RadioSourceErrorEvent, currentSource:
  RadioSource): Boolean =
  currentSource == error.source || state.activeSource.contains(error.source)

  /**
    * Returns a player action that switches to the specified radio source.
    *
    * @param source the radio source
    * @param delay  the delay
    * @return the player action
    */
  private def switchSourceAction(source: RadioSource, delay: FiniteDuration): PlayerAction =
  p => {
    p.makeToCurrentSource(source)
    p.startPlayback(delay)
  }

}

/**
  * A class responsible for error handling when playing internet radio.
  *
  * Because of failing network connections or temporarily unavailable radio
  * sources it is always possible that radio playback causes an error. This is
  * reflected by the radio player in form of
  * [[de.oliver_heger.linedj.player.engine.RadioSourceErrorEvent]] events.
  * When such an event occurs it has to be handled accordingly.
  *
  * This strategy class is responsible for handling such events. It is passed
  * the event, the current audio source, and the former error state. Based on
  * this information, it determines a follow-up state, and an action for the
  * radio player. The latter manipulates playback, e.g. switches to another
  * source.
  *
  * In principle, there can be multiple causes for playback errors: A specific
  * radio source may have a problem, e.g. it is temporarily unavailable or the
  * URL has changed, so that it will fail permanently. There could also be
  * general network problem; then playback of all sources is going to fail.
  *
  * This strategy tries to deal with all of these causes. At first, it tries to
  * restart the affected source multiple times with increasing pauses (the
  * number of retries, the minimum pause, and a factor to increase intervals
  * can be configured). If this fails, the player is instructed to switch to
  * another source, and the failing source is blacklisted. If the new source
  * causes again an error, the same steps are taken. In worst case (if there is
  * a general problem), all sources are eventually blacklisted. Then - after a
  * pause -, the blacklist is cleared, and attempts start again.
  *
  * It also has to be distinguished whether the current source or a replacement
  * source causes an error. In the case of a replacement source, the player is
  * instructed to try again with another replacement source (the error causing
  * source is again blacklisted).
  *
  * This implementation is purely functional. It is a class to simplify
  * dependency injection into the radio controller.
  */
class ErrorHandlingStrategy {

  import ErrorHandlingStrategy._

  /**
    * Function for error handling. This function produces a result indicating
    * how to handle the given error in the current state. The error state is
    * transformed to a new state, and an action is returned how to update the
    * radio player.
    *
    * @param config        the configuration settings for the strategy
    * @param previous      the previous error state
    * @param error         the current error event
    * @param currentSource the current source selected for playback
    * @return an action to update the player and the follow-up error state
    */
  def handleError(config: Config, previous: State, error: RadioSourceErrorEvent,
                  currentSource: RadioSource): (PlayerAction, State) =
  if (isActiveSource(previous, error, currentSource)) handleErrorCurrent(config, previous, error, currentSource)
  else handleErrorReplacement(config, previous, error)
}
