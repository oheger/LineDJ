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

import de.oliver_heger.linedj.player.engine.interval.IntervalQueries
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import de.oliver_heger.linedj.player.engine.radio._
import de.oliver_heger.linedj.radio.ErrorHandlingStrategy.{PlayerAction, State}
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.action.{ActionStore, FormAction}
import net.sf.jguiraffe.gui.builder.components.WidgetHandler
import net.sf.jguiraffe.gui.builder.components.model.{ListComponentHandler, ListModel, StaticTextHandler}
import net.sf.jguiraffe.gui.builder.event.FormChangeEvent
import net.sf.jguiraffe.resources.Message
import org.apache.commons.configuration.{Configuration, HierarchicalConfiguration, XMLConfiguration}
import org.mockito.ArgumentMatchers.{eq => argEq, _}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.mockito.{ArgumentCaptor, Mockito}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object RadioControllerSpec {
  /** Prefix for a radio source name. */
  private val RadioSourceName = "Radio_"

  /** Prefix for a radio source URI. */
  private val RadioSourceURI = "http://rad.io/"

  /** A text resource for the status line. */
  private val StatusText = "Text for the status line"

  /** The number of entries in the combo box. */
  private val ComboSize = 3

  /** The name of the start playback action. */
  private val StartPlaybackAction = "startPlaybackAction"

  /** The name of the stop playback action. */
  private val StopPlaybackAction = "stopPlaybackAction"

  /** A list with the names of the actions managed by the controller. */
  private val ActionNames = List(StartPlaybackAction, StopPlaybackAction)

  /** The recovery time in the player configuration. */
  private val RecoveryTime = 300.seconds

  /** The minimum number of failed sources to recover from. */
  private val MinFailedSources = 3

  /**
    * A data class storing information about radio sources passed to the mock
    * player's ''playSource()'' function.
    *
    * @param source      the radio source that was played
    * @param makeCurrent the make current flag
    * @param reset       the reset flag
    * @param delay       the delay
    */
  private case class RadioSourcePlayback(source: RadioSource,
                                         makeCurrent: Boolean,
                                         reset: Boolean,
                                         delay: FiniteDuration)

  /**
    * Generates the name for the source with the given index.
    *
    * @param idx the index
    * @return the name for this radio source
    */
  private def sourceName(idx: Int): String = RadioSourceName + idx

  /**
    * Generates a radio source object based on the given index.
    *
    * @param idx the index
    * @return the radio source for this index
    */
  private def radioSource(idx: Int): RadioSource =
    RadioSource(RadioSourceURI + idx)

  /**
    * Creates a configuration with settings for recovery.
    *
    * @return the configuration
    */
  private def createRecoveryConfiguration(): Configuration = {
    val config = new HierarchicalConfiguration
    config.addProperty("radio.error.recovery.time", RecoveryTime.toSeconds)
    config.addProperty("radio.error.recovery.minFailedSources", MinFailedSources)
    config
  }

  /**
    * Creates an error state with the given number of dysfunctional sources and
    * the given active source.
    *
    * @param srcCount  the number of dysfunctional sources
    * @param activeSrc the active radio source
    * @return the state
    */
  private def createStateWithErrorSources(srcCount: Int, activeSrc: RadioSource): ErrorHandlingStrategy
  .State = {
    val errorList = (1 to srcCount).map(i => radioSource(i)).toSet
    ErrorHandlingStrategy.NoError.copy(errorList = errorList, activeSource = Option(activeSrc))
  }
}

/**
  * Test class for ''RadioController''.
  */
class RadioControllerSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  import RadioControllerSpec._

  /**
    * Creates a mock source configuration that contains the given number of
    * radio sources. All sources are defined with their name and URI.
    *
    * @param count the number of radio sources
    * @return the configuration defining the number of radio sources
    */
  private def createSourceConfiguration(count: Int): RadioSourceConfig = {
    val sources = (1 to count) map (i => (sourceName(i), radioSource(i)))
    createSourceConfiguration(sources)
  }

  /**
    * Creates a configuration that contains the specified radio sources.
    *
    * @param sources a list with the sources to be contained
    * @return the configuration with these radio sources
    */
  private def createSourceConfiguration(sources: Seq[(String, RadioSource)]): RadioSourceConfig = {
    val config = mock[RadioSourceConfig]
    when(config.sources).thenReturn(sources)
    when(config.ranking(any(classOf[RadioSource]))).thenAnswer((invocation: InvocationOnMock) => {
      val src = invocation.getArguments.head.asInstanceOf[RadioSource]
      val index = src.uri.substring(RadioSourceURI.length)
      index.toInt
    })
    config
  }

  "A RadioController" should "create the configuration from the application context" in {
    val config = new XMLConfiguration("test-radio-configuration.xml")
    val helper = new RadioControllerTestHelper
    when(helper.applicationContext.getConfiguration).thenReturn(config)

    val ctrl = new RadioController(config, helper.applicationContext,
      helper.actionStore, helper.comboHandler, helper.statusHandler, helper.playbackTimeHandler,
      helper.metadataTextHandler, helper.errorIndicator, helper.errorHandlingStrategy)

    val playerConfig = ctrl.playerConfig
    playerConfig.errorConfig.retryInterval.toMillis should be(1000)
    playerConfig.sourceConfig.sources should have size 11
    playerConfig.initialDelay should be(1500)
  }

  it should "add radio sources to the combo box" in {
    val helper = new RadioControllerTestHelper
    helper.createInitializedController(createSourceConfiguration(4))

    helper.verifySourcesAddedToCombo(1, 2, 3, 4).verifySelectedSource(1)
      .verifyNoMoreInteractionWithCombo()
  }

  it should "handle missing source configurations correctly" in {
    val helper = new RadioControllerTestHelper

    helper.createInitializedController(RadioSourceConfig(new HierarchicalConfiguration))
    verify(helper.player).initSourceExclusions(any(), any())
    helper.verifySourcesAddedToCombo()
      .verifyNoMoreInteractionWithCombo()
      .verifyNoMorePlayerInteraction()
  }

  it should "disable playback actions if there are no sources" in {
    val helper = new RadioControllerTestHelper
    helper.createInitializedController(RadioSourceConfig(new HierarchicalConfiguration))

    helper.verifyAction(StartPlaybackAction, enabled = false)
      .verifyAction(StopPlaybackAction, enabled = false)
  }

  it should "set action states correctly if there are sources" in {
    val helper = new RadioControllerTestHelper
    helper.createInitializedController(createSourceConfiguration(1))

    helper.verifyAction(StartPlaybackAction, enabled = false)
      .verifyAction(StopPlaybackAction, enabled = true)
  }

  it should "start playback with the first source if not otherwise specified" in {
    val helper = new RadioControllerTestHelper
    helper.createInitializedController(createSourceConfiguration(1))

    helper.verifyInitialStartPlayback(radioSource(1))
  }

  it should "start playback with the stored source from the configuration" in {
    val srcConfig = createSourceConfiguration(4)
    val config = new HierarchicalConfiguration
    config.addProperty("radio.current", sourceName(2))
    val helper = new RadioControllerTestHelper
    helper.createInitializedController(srcConfig, config)

    helper.verifySelectedSource(2)
      .verifyInitialStartPlayback(radioSource(2))
  }

  it should "start playback with a configured delay" in {
    val helper = new RadioControllerTestHelper
    val config = new HierarchicalConfiguration
    val Delay = 1.second
    config.addProperty("radio.initialDelay", Delay.toMillis)
    helper.createInitializedController(createSourceConfiguration(1), mainConfig = config)

    helper.verifyInitialStartPlayback(radioSource(1), delay = Delay)
  }

  it should "store the current source in the user configuration after startup" in {
    val helper = new RadioControllerTestHelper
    val srcConfig = createSourceConfiguration(1)
    val config = new HierarchicalConfiguration
    helper.createInitializedController(srcConfig, config)

    config getString "radio.current" should be(sourceName(1))
  }

  it should "ignore the stored source if it cannot be resolved" in {
    val srcConfig = createSourceConfiguration(3)
    val config = new HierarchicalConfiguration
    config.addProperty("radio.current", sourceName(4))
    val helper = new RadioControllerTestHelper
    helper.createInitializedController(srcConfig, config)

    helper.verifyInitialStartPlayback(radioSource(1))
  }

  it should "react on changes in the radio sources selection while playback is not active" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createController(createSourceConfiguration(0))
    helper.sendPlayerInitializedMessage(ctrl)
    reset(helper.player)
    val src = radioSource(2)
    doReturn(src).when(helper.comboHandler).getData

    ctrl elementChanged mock[FormChangeEvent]
    helper.verifySwitchSource(radioSource(2))
      .verifyNoMorePlayerInteraction()
  }

  it should "react on changes in the radio sources selection while playback is active" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(2))
    val src = radioSource(2)
    doReturn(src).when(helper.comboHandler).getData

    ctrl elementChanged mock[FormChangeEvent]
    verify(helper.player).playSource(src, makeCurrent = true)
  }

  it should "update the current source in the config after a change in the combo selection" in {
    val srcConfig = createSourceConfiguration(3)
    val config = new HierarchicalConfiguration
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(srcConfig, config)
    val src = radioSource(2)
    doReturn(src).when(helper.comboHandler).getData

    ctrl elementChanged mock[FormChangeEvent]
    config getString "radio.current" should be(sourceName(2))
  }

  it should "reset the current metadata after changing the current source" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(2))
    ctrl.metadataChanged(RadioMetadataEvent(CurrentMetadata("some stale data")))
    val src = radioSource(2)
    doReturn(src).when(helper.comboHandler).getData

    ctrl elementChanged mock[FormChangeEvent]

    verify(helper.metadataTextHandler).setText("")
  }

  it should "handle an empty selection in the sources combo box" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createController(createSourceConfiguration(0))
    doReturn(null).when(helper.comboHandler).getData

    ctrl elementChanged mock[FormChangeEvent]
    helper.verifyNoMorePlayerInteraction()
  }

  it should "ignore change events while populating the sources combo box" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createController(createSourceConfiguration(4))
    doAnswer((_: InvocationOnMock) => {
      ctrl elementChanged mock[FormChangeEvent]
      null
    }).when(helper.comboHandler).addItem(anyInt(), any(), any())
    doReturn(radioSource(3)).when(helper.comboHandler).getData

    helper.sendPlayerInitializedMessage(ctrl)
    helper.verifyInitialStartPlayback(radioSource(1))
    verify(helper.player, never()).playSource(argEq(radioSource(2)), anyBoolean(), anyBoolean(), any())
  }

  it should "reset the sources update flag after the update" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(4))
    helper.verifyInitialStartPlayback(radioSource(1))
    val selectedSource = radioSource(2)

    doReturn(selectedSource).when(helper.comboHandler).getData
    ctrl.elementChanged(null)
    verify(helper.player).playSource(selectedSource, makeCurrent = true)
  }

  it should "send exclusion data to the radio player" in {
    val srcConfig = createSourceConfiguration(2)
    val exclusions = Map(radioSource(1) -> List(IntervalQueries.hours(0, 8)),
      radioSource(2) -> List(IntervalQueries.hours(9, 12)))
    when(srcConfig.exclusions).thenReturn(exclusions)
    val helper = new RadioControllerTestHelper

    helper.createInitializedController(srcConfig)
    val captRanking = ArgumentCaptor.forClass(classOf[RadioSource.Ranking])
    verify(helper.player).initSourceExclusions(argEq(exclusions), captRanking.capture())
    val ranking = captRanking.getValue
    ranking(radioSource(2)) should be(2)
    ranking(radioSource(8)) should be(8)
  }

  it should "update the status text when the current source is played" in {
    val helper = new RadioControllerTestHelper().expectResource("txt_status_playback")
    val ctrl = helper.createInitializedController(createSourceConfiguration(1))

    ctrl radioSourcePlaybackStarted radioSource(1)
    verify(helper.statusHandler).setText(StatusText)
  }

  it should "update the status text if a replacement source is played" in {
    val replaceSrc = radioSource(2)
    val msg = new Message(null, "txt_status_replacement", sourceName(2))
    val helper = new RadioControllerTestHelper().expectMessageResource(msg)
    val ctrl = helper.createInitializedController(createSourceConfiguration(2))

    ctrl radioSourcePlaybackStarted replaceSrc
    verify(helper.statusHandler).setText(StatusText)
  }

  it should "update the status text if an unknown replacement source is played" in {
    val replaceSrc = radioSource(2)
    val msg = new Message(null, "txt_status_replacement", replaceSrc.uri)
    val helper = new RadioControllerTestHelper().expectMessageResource(msg)
    val ctrl = helper.createInitializedController(createSourceConfiguration(1))

    ctrl radioSourcePlaybackStarted replaceSrc
    verify(helper.statusHandler).setText(StatusText)
  }

  it should "update the playback time" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(1))

    ctrl playbackTimeProgress 65.seconds
    verify(helper.playbackTimeHandler).setText("1:05")
  }

  it should "allow stopping playback" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createController(createSourceConfiguration(0))
    helper.sendPlayerInitializedMessage(ctrl)

    ctrl.stopPlayback()
    helper.verifyStopPlayback()
      .verifyAction(StartPlaybackAction, enabled = true)
      .verifyAction(StopPlaybackAction, enabled = false, count = 2)
  }

  it should "allow restarting playback" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(1))
    ctrl.stopPlayback()

    ctrl.startPlayback()
    verify(helper.player).playSource(radioSource(1), makeCurrent = false)
    helper.verifyAction(StartPlaybackAction, enabled = false, count = 2)
      .verifyAction(StopPlaybackAction, enabled = true, count = 2)
  }

  it should "set the playback active flag when restarting playback" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(2))
    ctrl.stopPlayback()
    ctrl.startPlayback()
    val src = radioSource(2)
    doReturn(src).when(helper.comboHandler).getData

    ctrl elementChanged mock[FormChangeEvent]
    val playback = helper.singlePlaybackFor(src)
    playback.makeCurrent shouldBe true
  }

  it should "pass a correct configuration to the error handling strategy" in {
    val mainConfig = new HierarchicalConfiguration
    mainConfig.addProperty("radio.error.maxRetries", 10)
    val srcConfig = createSourceConfiguration(4)
    val helper = new RadioControllerTestHelper
    val (answer, _) = helper.expectErrorStrategyCall()
    val ctrl = helper.createInitializedController(srcConfig, playbackSrcIdx = 1, mainConfig = mainConfig)

    ctrl playbackError RadioSourceErrorEvent(radioSource(1))
    answer.config.sourceConfig should be(srcConfig)
    answer.config.errorConfig.maxRetries should be(10)
  }

  it should "invoke the error handling strategy correctly" in {
    val srcConfig = createSourceConfiguration(8)
    val helper = new RadioControllerTestHelper
    val (answer, _) = helper.expectErrorStrategyCall()
    val error = RadioSourceErrorEvent(srcConfig.sources(2)._2)
    val ctrl = helper.createInitializedController(srcConfig, playbackSrcIdx = 2)

    ctrl playbackError error
    answer.errorSource should be(error.source)
    answer.currentSource should be(srcConfig.sources.head._2)
    answer.previousState should be(ErrorHandlingStrategy.NoError)
  }

  it should "invoke the player action from the error handling strategy" in {
    val helper = new RadioControllerTestHelper
    val (_, counter) = helper.expectErrorStrategyCall()
    val ctrl = helper.createInitializedController(createSourceConfiguration(2),
      playbackSrcIdx = 1)

    ctrl playbackError RadioSourceErrorEvent(radioSource(1))
    counter.get() should be(1)
  }

  it should "not handle a playback error if there is no playback source" in {
    val helper = new RadioControllerTestHelper
    val (_, counter) = helper.expectErrorStrategyCall()
    val ctrl = helper.createInitializedController(createSourceConfiguration(2))

    ctrl playbackError RadioSourceErrorEvent(radioSource(1))
    counter.get() should be(0)
  }

  it should "display the error indicator when a playback error was detected" in {
    val helper = new RadioControllerTestHelper
    helper.expectErrorStrategyCall()
    val ctrl = helper.createInitializedController(createSourceConfiguration(2),
      playbackSrcIdx = 1)

    ctrl playbackError RadioSourceErrorEvent(radioSource(1))
    verify(helper.errorIndicator).setVisible(true)
  }

  it should "record the next error state" in {
    val nextState = ErrorHandlingStrategy.NoError.copy(retryMillis = 10000)
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(8),
      playbackSrcIdx = 1)
    helper.injectErrorState(ctrl, nextState, 3)

    val (answer, _) = helper.expectErrorStrategyCall()
    ctrl radioSourcePlaybackStarted radioSource(3)
    ctrl playbackError RadioSourceErrorEvent(radioSource(3))
    answer.previousState should be(nextState)
  }

  it should "clear the error state when the user selects another source" in {
    val nextState = ErrorHandlingStrategy.NoError.copy(retryMillis = 15000)
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(8),
      playbackSrcIdx = 1)
    helper.injectErrorState(ctrl, nextState, 1)

    doReturn(radioSource(2)).when(helper.comboHandler).getData
    ctrl elementChanged mock[FormChangeEvent]
    verify(helper.errorIndicator).setVisible(false)
    val (answer, _) = helper.expectErrorStrategyCall()
    ctrl radioSourcePlaybackStarted radioSource(1)
    ctrl playbackError RadioSourceErrorEvent(radioSource(1))
    answer.previousState should be(ErrorHandlingStrategy.NoError)
  }

  it should "recover from an error after the configured time" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(8),
      mainConfig = createRecoveryConfiguration(), playbackSrcIdx = 7)
    helper.injectErrorState(ctrl, createStateWithErrorSources(MinFailedSources, radioSource(7)), 1)

    ctrl playbackTimeProgress RecoveryTime
    verify(helper.player).playSource(radioSource(1), makeCurrent = false)
    verify(helper.errorIndicator).setVisible(false)
    val (answer, _) = helper.expectErrorStrategyCall()
    ctrl radioSourcePlaybackStarted radioSource(2)
    ctrl playbackError RadioSourceErrorEvent(radioSource(2))
    answer.previousState should be(ErrorHandlingStrategy.NoError)
  }

  it should "not change the source on recovery if it is already played" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(4),
      mainConfig = createRecoveryConfiguration(), playbackSrcIdx = 1)
    helper.injectErrorState(ctrl, createStateWithErrorSources(MinFailedSources + 1,
      radioSource(1)), 1)

    ctrl playbackTimeProgress RecoveryTime
    verify(helper.errorIndicator).setVisible(false)
    helper.verifyNoMorePlayerInteraction()
  }

  it should "not recover from error if not in error state" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(2),
      createRecoveryConfiguration())

    ctrl playbackTimeProgress RecoveryTime
    helper.verifyNoRecovery()
  }

  it should "not recover if the recovery time is not yet reached" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(2),
      createRecoveryConfiguration())
    helper.injectErrorState(ctrl, createStateWithErrorSources(MinFailedSources,
      radioSource(1)), 1)

    ctrl playbackTimeProgress RecoveryTime - 1.second
    helper.verifyNoRecovery()
  }

  it should "not recover if the number of dysfunctional sources is too small" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(2),
      createRecoveryConfiguration())
    helper.injectErrorState(ctrl, createStateWithErrorSources(MinFailedSources - 1,
      radioSource(1)), 1)

    ctrl playbackTimeProgress RecoveryTime
    helper.verifyNoRecovery()
  }

  it should "handle an event about a failed playback context creation" in {
    val helper = new RadioControllerTestHelper
    val (_, counter) = helper.expectErrorStrategyCall()
    val ctrl = helper.createInitializedController(createSourceConfiguration(2),
      playbackSrcIdx = 1)

    ctrl.playbackContextCreationFailed()
    counter.get() should be(1)
  }

  it should "not handle a failed playback context creation after a playback error" in {
    val srcConfig = createSourceConfiguration(8)
    val helper = new RadioControllerTestHelper
    val (_, counter) = helper.expectErrorStrategyCall()
    val errorSource = radioSource(1)
    val ctrl = helper.createInitializedController(srcConfig)
    ctrl radioSourcePlaybackStarted errorSource
    ctrl playbackError RadioSourceErrorEvent(errorSource)

    ctrl.playbackContextCreationFailed()
    counter.get() should be(1)
  }

  it should "record a replacement source and start playback of it later" in {
    val repSrc = radioSource(2)
    val msg = new Message(null, "txt_status_replacement", sourceName(2))
    val helper = new RadioControllerTestHelper
    helper.expectMessageResource(msg)
    val ctrl = helper.createInitializedController(createSourceConfiguration(4))
    ctrl.stopPlayback()

    ctrl replacementSourceStarts repSrc
    helper.playbacksFor(repSrc) should have size 0
    ctrl.startPlayback()
    val playback = helper.singlePlaybackFor(repSrc)
    playback.makeCurrent shouldBe false
    verify(helper.statusHandler).setText(StatusText)
  }

  it should "directly switch to a replacement source if playback is enabled" in {
    val repSrc = radioSource(3)
    val msg = new Message(null, "txt_status_replacement", sourceName(3))
    val helper = new RadioControllerTestHelper
    helper.expectMessageResource(msg)
    val ctrl = helper.createInitializedController(createSourceConfiguration(4))

    ctrl replacementSourceStarts repSrc
    helper.singlePlaybackFor(repSrc).makeCurrent shouldBe false
    verify(helper.statusHandler, never()).setText(anyString())
  }

  it should "handle an end of the replacement source if playback is not active" in {
    val repSrc = radioSource(2)
    val helper = new RadioControllerTestHelper
    helper.expectResource("txt_status_playback")
    val ctrl = helper.createInitializedController(createSourceConfiguration(4))
    ctrl.stopPlayback()

    ctrl replacementSourceStarts repSrc
    ctrl.replacementSourceEnds()
    ctrl.startPlayback()
    helper.playbacksFor(repSrc) should have size 0
    val pb = helper.playbacksFor(radioSource(1))
    pb should have size 2
    pb.head.makeCurrent shouldBe false
    verify(helper.statusHandler).setText(StatusText)
  }

  it should "handle an end of the replacement source if playback is active" in {
    val repSrc = radioSource(3)
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(4))

    ctrl replacementSourceStarts repSrc
    ctrl.replacementSourceEnds()
    helper.singlePlaybackFor(repSrc).makeCurrent shouldBe false
    val pb = helper.playbacksFor(radioSource(1))
    pb should have size 2
    pb.head.makeCurrent shouldBe false
    verifyNoInteractions(helper.statusHandler)
  }

  it should "handle a failed initialization of the player" in {
    val ErrorText = "Player error"
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createController(createSourceConfiguration(0))
    helper.expectResource("txt_status_error", ErrorText)

    ctrl receive RadioController.RadioPlayerInitialized(Failure(new IllegalStateException("No player")))

    verify(helper.statusHandler).setText(ErrorText)
    verifyNoInteractions(helper.errorIndicator)
  }

  it should "handle an event about unsupported metadata" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(5))
    ctrl.metadataChanged(RadioMetadataEvent(CurrentMetadata("some data")))

    ctrl.metadataChanged(RadioMetadataEvent(MetadataNotSupported))

    verify(helper.metadataTextHandler).setText("")
  }

  it should "handle an event about updated metadata" in {
    val MetadataContent = "Test song from Test Artist"
    val metadata = CurrentMetadata(s"StreamTitle='$MetadataContent';")
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(1))

    ctrl.metadataChanged(RadioMetadataEvent(metadata))

    verify(helper.metadataTextHandler).setText(MetadataContent)
  }

  it should "correctly rotate metadata text" in {
    val MetadataContent = "0123456789ABCDEF"
    val mainConfig = new HierarchicalConfiguration
    mainConfig.addProperty("radio.metadataMaxLen", "10")
    mainConfig.addProperty("radio.metadataRotateScale", 2.0)
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(1), mainConfig = mainConfig)

    ctrl.playbackTimeProgress(10.seconds)
    ctrl.metadataChanged(RadioMetadataEvent(CurrentMetadata(MetadataContent)))
    verify(helper.metadataTextHandler).setText("0123456789")

    ctrl.playbackTimeProgress(11.seconds)
    verify(helper.metadataTextHandler).setText("23456789AB")
  }

  it should "not update metadata if the same event arrives again" in {
    val metadata = CurrentMetadata("This is some metadata")
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(1))

    ctrl.metadataChanged(RadioMetadataEvent(metadata))
    ctrl.metadataChanged(RadioMetadataEvent(metadata))

    verify(helper.metadataTextHandler, times(1)).setText(metadata.title)
  }

  it should "reset the playback time when switching to another source" in {
    val mainConfig = new HierarchicalConfiguration
    mainConfig.addProperty("radio.metadataMaxLen", "10")
    val helper = new RadioControllerTestHelper
    doReturn(radioSource(2)).when(helper.comboHandler).getData
    val ctrl = helper.createInitializedController(createSourceConfiguration(4), mainConfig = mainConfig)
    ctrl.playbackTimeProgress(10.seconds)

    ctrl.elementChanged(mock[FormChangeEvent])
    ctrl.metadataChanged(RadioMetadataEvent(CurrentMetadata("0123456789ABCDEF")))
    ctrl.playbackTimeProgress(3.seconds)

    verify(helper.metadataTextHandler).setText("3456789ABC")
  }

  /**
    * A helper class managing the dependencies of the test object.
    */
  private class RadioControllerTestHelper {
    /** Stores the sources that have been played. */
    var playedSources = List.empty[RadioSourcePlayback]

    /** Mock for the radio player. */
    val player: RadioPlayer = createRadioPlayerMock()

    /** Mock for the application context. */
    val applicationContext: ApplicationContext = mock[ApplicationContext]

    /** Mock for the combo box handler. */
    val comboHandler: ListComponentHandler = createComboHandlerMock()

    /** Mock for the status line handler. */
    val statusHandler: StaticTextHandler = mock[StaticTextHandler]

    /** Mock for the handler for the playback time. */
    val playbackTimeHandler: StaticTextHandler = mock[StaticTextHandler]

    /** Mock for the handler for the metadata. */
    val metadataTextHandler: StaticTextHandler = mock[StaticTextHandler]

    /** Mock for the error indicator control. */
    val errorIndicator: WidgetHandler = mock[WidgetHandler]

    /** Mock for the error handling strategy. */
    val errorHandlingStrategy: ErrorHandlingStrategy = mock[ErrorHandlingStrategy]

    /** A map with mock actions. */
    private val actions = createActionMap()

    /** Mock for the action store. */
    val actionStore: ActionStore = createActionStore(actions)

    /**
      * Creates a test instance of a radio controller.
      *
      * @param srcConfig  the configuration for the radio sources
      * @param userConfig the user configuration
      * @param mainConfig the main configuration of the application
      * @return the test instance
      */
    def createController(srcConfig: RadioSourceConfig,
                         userConfig: Configuration = new HierarchicalConfiguration,
                         mainConfig: Configuration = new HierarchicalConfiguration()): RadioController = {
      val playerConfig = RadioPlayerConfig(mainConfig).copy(sourceConfig = srcConfig)
      new RadioController(userConfig, applicationContext, actionStore, comboHandler,
        statusHandler, playbackTimeHandler, metadataTextHandler, errorIndicator, errorHandlingStrategy, playerConfig)
    }

    /**
      * Creates a radio controller test instance and initializes it by sending
      * it a message with the radio player.
      *
      * @param srcConfig           the configuration for the radio sources
      * @param userConfig          the user configuration
      * @param mainConfig          the main configuration of the application
      * @param resetErrorIndicator flag whether the error indicator mock
      *                            should be reset (the indicator is hidden
      *                            at initialization time)
      * @param playbackSrcIdx      an index for the current source to be set;
      *                            negative for none
      * @return the test instance
      */
    def createInitializedController(srcConfig: RadioSourceConfig,
                                    userConfig: Configuration = new HierarchicalConfiguration,
                                    mainConfig: Configuration = new HierarchicalConfiguration,
                                    resetErrorIndicator: Boolean = true,
                                    playbackSrcIdx: Int = -1): RadioController = {
      val ctrl = createController(srcConfig, userConfig, mainConfig)
      sendPlayerInitializedMessage(ctrl)
      verify(errorIndicator).setVisible(false)
      if (playbackSrcIdx >= 0) {
        ctrl radioSourcePlaybackStarted radioSource(playbackSrcIdx)
      }
      if (resetErrorIndicator) {
        reset(errorIndicator)
      }
      ctrl
    }

    /**
      * Sends a message to the given controller that the managed radio player
      * could be initialized successfully.
      *
      * @param ctrl the controller
      */
    def sendPlayerInitializedMessage(ctrl: RadioController): Unit = {
      ctrl receive RadioController.RadioPlayerInitialized(Success(player))
    }

    /**
      * Verifies that the provided sources have been added to the combo box (in
      * this order). Also checks that existing entries were removed before.
      *
      * @param expSources the expected sources to be added
      * @return this test helper
      */
    def verifySourcesAddedToCombo(expSources: Int*): RadioControllerTestHelper = {
      val verInOrder = Mockito.inOrder(comboHandler)
      verInOrder.verify(comboHandler).getListModel
      (1 to ComboSize).reverse foreach { i =>
        verInOrder.verify(comboHandler).removeItem(i - 1)
      }
      expSources.zipWithIndex foreach { e =>
        verInOrder.verify(comboHandler).addItem(e._2, sourceName(e._1), radioSource(e._1))
      }
      this
    }

    /**
      * Verifies that the specified radio source was set as selected element in
      * the radio sources combo box.
      *
      * @param src the radio source
      * @return this test helper
      */
    def verifySelectedSource(src: RadioSource): RadioControllerTestHelper = {
      verify(comboHandler).setData(src)
      this
    }

    /**
      * Verifies that the radio source with the specified index was set as
      * selected element in the radio sources combo box.
      *
      * @param idx the index of the radio source
      * @return this test helper
      */
    def verifySelectedSource(idx: Int): RadioControllerTestHelper =
      verifySelectedSource(radioSource(idx))

    /**
      * Checks that there was no further interaction with the combo box for
      * radio sources.
      *
      * @return this test helper
      */
    def verifyNoMoreInteractionWithCombo(): RadioControllerTestHelper = {
      verifyNoMoreInteractions(comboHandler)
      this
    }

    /**
      * Verifies whether the correct state for the specified action has been
      * set.
      *
      * @param name    the name of the action
      * @param enabled the expected state
      * @param count   the number of expected invocations
      * @return this test helper
      */
    def verifyAction(name: String, enabled: Boolean, count: Int = 1): RadioControllerTestHelper = {
      verify(actions(name), times(count)).setEnabled(enabled)
      this
    }

    /**
      * Verifies that the player was triggered to switch to the specified
      * source.
      *
      * @param src the source
      * @return this test helper
      */
    def verifySwitchSource(src: RadioSource): RadioControllerTestHelper = {
      verify(player).makeToCurrentSource(src)
      this
    }

    /**
      * Verifies that playback has been started initially after the startup of
      * the controller.
      *
      * @param src   the source to be played
      * @param delay the expected delay
      * @return this test helper
      */
    def verifyInitialStartPlayback(src: RadioSource,
                                   delay: FiniteDuration = RadioController.DefaultInitialDelay.millis):
    RadioControllerTestHelper = {
      val playback = playbacksFor(src)
        .find(src => src.makeCurrent && !src.reset && src.delay == delay)
      playback.isDefined shouldBe true
      this
    }

    /**
      * Verifies that playback was stopped.
      *
      * @return this test helper
      */
    def verifyStopPlayback(): RadioControllerTestHelper = {
      verify(player).stopPlayback()
      this
    }

    /**
      * Verifies that there were no further interactions with the radio player.
      *
      * @return this test helper
      */
    def verifyNoMorePlayerInteraction(): RadioControllerTestHelper = {
      verifyNoMoreInteractions(player)
      this
    }

    /**
      * Prepares the mock application context for a resource request based on a
      * resource key.
      *
      * @param key  the resource key
      * @param text the text to be returned
      * @return this test helper
      */
    def expectResource(key: AnyRef, text: String = StatusText): RadioControllerTestHelper = {
      when(applicationContext.getResourceText(key)).thenReturn(text)
      this
    }

    /**
      * Prepares the mock application context for a resource request based on a
      * ''Message'' object.
      *
      * @param msg  the expected message object
      * @param text the text to be returned
      * @return this test helper
      */
    def expectMessageResource(msg: Message, text: String = StatusText): RadioControllerTestHelper = {
      when(applicationContext.getResourceText(msg)).thenReturn(text)
      this
    }

    /**
      * Prepares the mock error handling strategy to expect an invocation. An
      * answer is set that records the invocation. Also, a player action is
      * created that records its invocation.
      *
      * @param nextState the next state to be returned
      * @return the mock answer for the strategy and a counter for action calls
      */
    def expectErrorStrategyCall(nextState: ErrorHandlingStrategy.State =
                                ErrorHandlingStrategy.NoError):
    (ErrorStrategyAnswer, AtomicInteger) = {
      val counter = new AtomicInteger
      val action: ErrorHandlingStrategy.PlayerAction = p => {
        p should be(player)
        counter.incrementAndGet()
      }
      val answer = new ErrorStrategyAnswer(action, nextState)
      when(errorHandlingStrategy.handleError(any[RadioPlayerConfig],
        any[ErrorHandlingStrategy.State], any[RadioSourceErrorEvent], any[RadioSource]))
        .thenAnswer(answer)
      (answer, counter)
    }

    /**
      * Invokes the given test controller to bring it into a specific error
      * state.
      *
      * @param ctrl  the controller
      * @param state the error state
      * @param idx   the index of the source for the error event
      */
    def injectErrorState(ctrl: RadioController, state: ErrorHandlingStrategy.State,
                         idx: Int): (ErrorStrategyAnswer, AtomicInteger) = {
      val t = expectErrorStrategyCall(state)
      ctrl playbackError RadioSourceErrorEvent(radioSource(idx))
      reset(errorIndicator, errorHandlingStrategy, player)
      t
    }

    /**
      * Verifies that no error recovery has been done. This is done via the
      * error indicator mock.
      *
      * @return this test helper
      */
    def verifyNoRecovery(): RadioControllerTestHelper = {
      verify(errorIndicator, never()).setVisible(false)
      this
    }

    /**
      * Returns information about playbacks of the source specified.
      *
      * @param source the source in question
      * @return a list with playback operations for this source
      */
    def playbacksFor(source: RadioSource): List[RadioSourcePlayback] =
      playedSources.filter(_.source == source)

    /**
      * Verifies that there was a single playback of the given source and
      * returns information about it.
      *
      * @param source the source in question
      * @return the information about the playback of this source
      */
    def singlePlaybackFor(source: RadioSource): RadioSourcePlayback = {
      val playbacks = playbacksFor(source)
      playbacks should have size 1
      playbacks.head
    }

    /**
      * Creates a mock for the combo box handler.
      *
      * @return the mock combo box handler
      */
    private def createComboHandlerMock(): ListComponentHandler = {
      val handler = mock[ListComponentHandler]
      val model = mock[ListModel]
      when(handler.getListModel).thenReturn(model)
      when(model.size()).thenReturn(ComboSize)
      handler
    }

    /**
      * Creates mock actions for the actions managed by the controller and
      * returns a map for accessing them by name.
      *
      * @return the map with actions
      */
    private def createActionMap(): Map[String, FormAction] =
      ActionNames.map((_, mock[FormAction])).toMap

    /**
      * Creates a mock for the action store that manages the specified actions.
      *
      * @param actionMap a map with supported actions
      * @return the mock action store
      */
    private def createActionStore(actionMap: Map[String, FormAction]): ActionStore = {
      val store = mock[ActionStore]
      actionMap foreach { e =>
        when(store.getAction(e._1)).thenReturn(e._2)
      }
      store
    }

    /**
      * Creates the mock radio player and configures it to store information
      * about radio sources that have been played. Obviously, Mockito has
      * problems with Scala's default parameters; it cannot correctly resolve
      * radio sources in its ''verify()'' methods. Therefore, the sources are
      * stored and evaluated manually.
      *
      * @return the mock radio player
      */
    private def createRadioPlayerMock(): RadioPlayer = {
      val player = mock[RadioPlayer]
      when(player.playSource(any(), anyBoolean(), anyBoolean(), any()))
        .thenAnswer((invocation: InvocationOnMock) => {
          val playback = RadioSourcePlayback(invocation.getArgument(0, classOf[RadioSource]),
            invocation.getArgument(1, classOf[java.lang.Boolean]),
            invocation.getArgument(2, classOf[java.lang.Boolean]),
            invocation.getArgument(3, classOf[FiniteDuration]))
          playedSources = playback :: playedSources
        })
      player
    }
  }

  /**
    * An answer class to check an invocation of the error handling strategy.
    *
    * @param action the action to be returned
    * @param state  the next state to be returned
    */
  private class ErrorStrategyAnswer(action: ErrorHandlingStrategy.PlayerAction,
                                    state: ErrorHandlingStrategy.State)
    extends Answer[(ErrorHandlingStrategy.PlayerAction, ErrorHandlingStrategy.State)] {
    /** The config passed to the strategy. */
    var config: RadioPlayerConfig = _

    /** The previous state passed to the strategy. */
    var previousState: ErrorHandlingStrategy.State = _

    /** The radio source causing an error. */
    var errorSource: RadioSource = _

    /** The current source. */
    var currentSource: RadioSource = _

    override def answer(invocation: InvocationOnMock): (PlayerAction, State) = {
      config = invocation.getArguments.head.asInstanceOf[RadioPlayerConfig]
      previousState = invocation.getArguments()(1).asInstanceOf[ErrorHandlingStrategy.State]
      errorSource = invocation.getArguments()(2).asInstanceOf[RadioSourceErrorEvent].source
      currentSource = invocation.getArguments()(3).asInstanceOf[RadioSource]
      (action, state)
    }
  }

}
