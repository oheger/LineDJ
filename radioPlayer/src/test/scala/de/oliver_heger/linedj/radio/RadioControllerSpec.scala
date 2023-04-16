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

package de.oliver_heger.linedj.radio

import de.oliver_heger.linedj.player.engine.interval.IntervalQueries
import de.oliver_heger.linedj.player.engine.radio._
import de.oliver_heger.linedj.player.engine.radio.config.RadioSourceConfig
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.action.{ActionStore, FormAction}
import net.sf.jguiraffe.gui.builder.components.model.{ListComponentHandler, ListModel, StaticTextHandler}
import net.sf.jguiraffe.gui.builder.event.FormChangeEvent
import org.apache.commons.configuration.{HierarchicalConfiguration, XMLConfiguration}
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object RadioControllerSpec {
  /** Prefix for a radio source name. */
  private val RadioSourceName = "Radio_"

  /** Prefix for a radio source URI. */
  private val RadioSourceURI = "http://rad.io/"

  /** The number of entries in the combo box. */
  private val ComboSize = 3

  /** The name of the start playback action. */
  private val StartPlaybackAction = "startPlaybackAction"

  /** The name of the stop playback action. */
  private val StopPlaybackAction = "stopPlaybackAction"

  /** A list with the names of the actions managed by the controller. */
  private val ActionNames = List(StartPlaybackAction, StopPlaybackAction)

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
    when(config.namedSources).thenReturn(sources)
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

    val ctrl = new RadioController(config, helper.applicationContext, helper.actionStore, helper.comboHandler,
      helper.metadataTextHandler, helper.statusLineController)

    val playerConfig = ctrl.playerConfig
    playerConfig.sourceConfig.namedSources should have size 11
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

    helper.createInitializedController(RadioSourceConfigLoader.load(new HierarchicalConfiguration))
    verify(helper.player).initRadioSourceConfig(any())
    verify(helper.player).initMetadataConfig(any())
    helper.verifySourcesAddedToCombo()
      .verifyNoMoreInteractionWithCombo()
      .verifyNoMorePlayerInteraction()
  }

  it should "disable playback actions if there are no sources" in {
    val helper = new RadioControllerTestHelper
    helper.createInitializedController(RadioSourceConfigLoader.load(new HierarchicalConfiguration))

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

    helper.verifySourcePlayback(radioSource(1))
  }

  it should "start playback with the stored source from the configuration" in {
    val srcConfig = createSourceConfiguration(4)
    val config = new HierarchicalConfiguration
    config.addProperty("radio.current", sourceName(2))
    val helper = new RadioControllerTestHelper
    helper.createInitializedController(srcConfig, config)

    helper.verifySelectedSource(2)
      .verifySourcePlayback(radioSource(2))
  }

  it should "start playback with a configured delay" in {
    val helper = new RadioControllerTestHelper
    val config = new HierarchicalConfiguration
    helper.createInitializedController(createSourceConfiguration(1), mainConfig = config)

    helper.verifySourcePlayback(radioSource(1))
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

    helper.verifySourcePlayback(radioSource(1))
  }

  it should "react on changes in the radio sources selection" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(2))
    val src = radioSource(2)
    doReturn(src).when(helper.comboHandler).getData

    ctrl elementChanged mock[FormChangeEvent]
    helper.verifySwitchSource(radioSource(2))
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
    ctrl.metadataChanged(RadioMetadataEvent(radioSource(1), CurrentMetadata("some stale data")))
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
    helper.verifySourcePlayback(radioSource(1))
    verify(helper.player, never()).switchToRadioSource(radioSource(2))
  }

  it should "reset the sources update flag after the update" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(4))
    helper.verifySourcePlayback(radioSource(1))
    val selectedSource = radioSource(2)

    doReturn(selectedSource).when(helper.comboHandler).getData
    ctrl.elementChanged(null)
    helper.verifySwitchSource(selectedSource)
  }

  it should "send exclusion data to the radio player" in {
    val srcConfig = createSourceConfiguration(2)
    val exclusions = Map(radioSource(1) -> List(IntervalQueries.hours(0, 8)),
      radioSource(2) -> List(IntervalQueries.hours(9, 12)))
    when(srcConfig.exclusions(any())).thenAnswer((invocation: InvocationOnMock) => {
      val source = invocation.getArgument[RadioSource](0)
      exclusions(source)
    })
    val helper = new RadioControllerTestHelper

    helper.createInitializedController(srcConfig)

    verify(helper.player).initRadioSourceConfig(srcConfig)
  }

  it should "initialize the metadata configuration on the radio player" in {
    val helper = new RadioControllerTestHelper

    val controller = helper.createInitializedController(createSourceConfiguration(1))

    verify(helper.player).initMetadataConfig(controller.playerConfig.metadataConfig)
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
    verify(helper.player, times(2)).startPlayback()
    helper.verifyAction(StartPlaybackAction, enabled = false, count = 2)
      .verifyAction(StopPlaybackAction, enabled = true, count = 2)
  }

  it should "handle a failed initialization of the player" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createController(createSourceConfiguration(0))

    ctrl receive RadioController.RadioPlayerInitialized(Failure(new IllegalStateException("No player")))

    verify(helper.statusLineController).playerInitializationFailed()
  }

  it should "handle an event about unsupported metadata" in {
    val src = radioSource(1)
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(5))
    ctrl.metadataChanged(RadioMetadataEvent(src, CurrentMetadata("some data")))

    ctrl.metadataChanged(RadioMetadataEvent(src, MetadataNotSupported))

    verify(helper.metadataTextHandler).setText("")
  }

  it should "handle an event about updated metadata" in {
    val MetadataContent = "Test song from Test Artist"
    val metadata = CurrentMetadata(s"StreamTitle='$MetadataContent';")
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(1))

    ctrl.metadataChanged(RadioMetadataEvent(radioSource(1), metadata))

    verify(helper.metadataTextHandler).setText(MetadataContent)
  }

  it should "correctly rotate metadata text" in {
    val MetadataContent = "0123456789ABCDEF"
    val mainConfig = new HierarchicalConfiguration
    mainConfig.addProperty("radio.metadataMaxLen", "10")
    mainConfig.addProperty("radio.metadataRotateSpeed", 4.0)
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(1), mainConfig = mainConfig)

    ctrl.playbackTimeProgress(10.seconds)
    ctrl.metadataChanged(RadioMetadataEvent(radioSource(1), CurrentMetadata(MetadataContent)))
    verify(helper.metadataTextHandler).setText("0123456789")

    ctrl.playbackTimeProgress(10.seconds + 250.millis)
    verify(helper.metadataTextHandler).setText("123456789A")

    ctrl.playbackTimeProgress(11.seconds)
    verify(helper.metadataTextHandler).setText("456789ABCD")
  }

  it should "not update metadata if the same event arrives again" in {
    val metadata = CurrentMetadata("This is some metadata")
    val event = RadioMetadataEvent(radioSource(1), metadata)
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(1))

    ctrl.metadataChanged(event)
    ctrl.metadataChanged(event)

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
    ctrl.metadataChanged(RadioMetadataEvent(radioSource(2), CurrentMetadata("0123456789ABCDEF")))
    ctrl.playbackTimeProgress(3.seconds)

    verify(helper.metadataTextHandler).setText("3456789ABC")
  }

  /**
    * A helper class managing the dependencies of the test object.
    */
  private class RadioControllerTestHelper {
    /** Mock for the radio player. */
    val player: RadioPlayer = mock[RadioPlayer]

    /** Mock for the status line controller. */
    val statusLineController: RadioStatusLineController = mock[RadioStatusLineController]

    /** Mock for the application context. */
    val applicationContext: ApplicationContext = mock[ApplicationContext]

    /** Mock for the combo box handler. */
    val comboHandler: ListComponentHandler = createComboHandlerMock()

    /** Mock for the handler for the metadata. */
    val metadataTextHandler: StaticTextHandler = mock[StaticTextHandler]

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
                         userConfig: HierarchicalConfiguration = new HierarchicalConfiguration,
                         mainConfig: HierarchicalConfiguration = new HierarchicalConfiguration()): RadioController = {
      val playerConfig = RadioPlayerClientConfig(mainConfig).copy(sourceConfig = srcConfig)
      new RadioController(userConfig, applicationContext, actionStore, comboHandler,
        metadataTextHandler, statusLineController, playerConfig)
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
                                    userConfig: HierarchicalConfiguration = new HierarchicalConfiguration,
                                    mainConfig: HierarchicalConfiguration = new HierarchicalConfiguration,
                                    resetErrorIndicator: Boolean = true,
                                    playbackSrcIdx: Int = -1): RadioController = {
      val ctrl = createController(srcConfig, userConfig, mainConfig)
      sendPlayerInitializedMessage(ctrl)
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
      verify(statusLineController).updateCurrentSource(src)
      verify(player).switchToRadioSource(src)
      this
    }

    /**
      * Verifies that playback of the given source has been started.
      *
      * @param src the source to be played
      * @return this test helper
      */
    def verifySourcePlayback(src: RadioSource): RadioControllerTestHelper = {
      verifySwitchSource(src)
      verify(player).startPlayback()
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
  }
}
