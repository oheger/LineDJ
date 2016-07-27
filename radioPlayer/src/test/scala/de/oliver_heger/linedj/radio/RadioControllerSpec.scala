/*
 * Copyright 2015-2016 The Developers Team.
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

import de.oliver_heger.linedj.player.engine.RadioSource
import de.oliver_heger.linedj.player.engine.facade.RadioPlayer
import de.oliver_heger.linedj.player.engine.interval.IntervalQueries
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.action.{ActionStore, FormAction}
import net.sf.jguiraffe.gui.builder.components.model.{ListComponentHandler, ListModel, StaticTextHandler}
import net.sf.jguiraffe.gui.builder.event.FormChangeEvent
import net.sf.jguiraffe.gui.builder.window.WindowEvent
import net.sf.jguiraffe.resources.Message
import org.apache.commons.configuration.{Configuration, HierarchicalConfiguration}
import org.mockito.Matchers._
import org.mockito.Matchers.{eq => argEq}
import org.mockito.Mockito
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._

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
class RadioControllerSpec extends FlatSpec with Matchers with MockitoSugar {

  import RadioControllerSpec._

  /**
    * Creates a test window event of the specified type.
    *
    * @return the test window event
    */
  private def event(): WindowEvent = mock[WindowEvent]

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
    config
  }

  "A RadioController" should "create a default source config factory" in {
    val helper = new RadioControllerTestHelper
    val config = new HierarchicalConfiguration
    config.addProperty("radio.sources.source.name", RadioSourceName)
    config.addProperty("radio.sources.source.uri", RadioSourceURI)

    val ctrl = new RadioController(helper.player, config, helper.applicationContext,
      helper.actionStore, helper.comboHandler, helper.statusHandler, helper.playbackTimeHandler)
    val srcConfig = ctrl.configFactory(config)
    srcConfig.sources should have size 1
    srcConfig.sources.head._1 should be(RadioSourceName)
    srcConfig.sources.head._2.uri should be(RadioSourceURI)
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
    helper.verifySourcesAddedToCombo().verifyNoMoreInteractionWithCombo()
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

    helper.verifySwitchSource(radioSource(1)).verifyStartPlayback()
  }

  it should "start playback with the stored source from the configuration" in {
    val srcConfig = createSourceConfiguration(4)
    val config = new HierarchicalConfiguration
    config.addProperty("radio.current", sourceName(2))
    val helper = new RadioControllerTestHelper
    helper.createInitializedController(srcConfig, config)

    helper.verifySelectedSource(2).verifySwitchSource(radioSource(2)).verifyStartPlayback()
  }

  it should "start playback with a configured delay" in {
    val helper = new RadioControllerTestHelper
    val config = new HierarchicalConfiguration
    val Delay = 1.second
    config.addProperty("radio.initialDelay", Delay.toMillis)
    helper.createInitializedController(createSourceConfiguration(1), config)

    helper.verifySwitchSource(radioSource(1), Delay).verifyStartPlayback()
  }

  it should "store the current source in the configuration after startup" in {
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

    helper.verifySwitchSource(radioSource(1)).verifyStartPlayback()
  }

  it should "react on changes in the radio sources selection" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createController(createSourceConfiguration(0))
    val src = radioSource(2)
    doReturn(src).when(helper.comboHandler).getData

    ctrl elementChanged mock[FormChangeEvent]
    helper.verifySwitchSource(radioSource(2), null)
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

  it should "handle an empty selection in the sources combo box" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createController(createSourceConfiguration(0))
    doReturn(null).when(helper.comboHandler).getData

    ctrl elementChanged mock[FormChangeEvent]
    verifyZeroInteractions(helper.player)
  }

  it should "ignore change events while populating the sources combo box" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createController(createSourceConfiguration(4))
    doAnswer(new Answer[AnyRef] {
      override def answer(invocation: InvocationOnMock): AnyRef = {
        ctrl elementChanged mock[FormChangeEvent]
        null
      }
    }).when(helper.comboHandler).addItem(anyInt(), anyObject(), anyObject())
    doReturn(radioSource(3)).when(helper.comboHandler).getData

    ctrl windowOpened event()
    helper.verifySwitchSource(radioSource(1)).verifyStartPlayback()
    verify(helper.player, never()).switchToSource(argEq(radioSource(2)), any[FiniteDuration])
  }

  it should "reset the sources update flag after the update" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(4))
    helper verifySwitchSource radioSource(1)
    val selectedSource = radioSource(2)

    doReturn(selectedSource).when(helper.comboHandler).getData
    ctrl.elementChanged(null)
    helper.verifySwitchSource(selectedSource, null)
  }

  it should "send exclusion data to the radio player" in {
    val srcConfig = createSourceConfiguration(2)
    val exclusions = Map(radioSource(1) -> List(IntervalQueries.hours(0, 8)),
      radioSource(2) -> List(IntervalQueries.hours(9, 12)))
    when(srcConfig.exclusions).thenReturn(exclusions)
    val helper = new RadioControllerTestHelper

    helper.createInitializedController(srcConfig)
    verify(helper.player).initSourceExclusions(exclusions)
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

    ctrl playbackTimeProgress 65
    verify(helper.playbackTimeHandler).setText("1:05")
  }

  /**
    * Helper method for testing that an event is ignored. It is only checked
    * that the event is not touched.
    *
    * @param f a function that invokes a controller method with an event
    */
  private def checkIgnoredEvent(f: (RadioController, WindowEvent) => Unit): Unit = {
    val ev = event()
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createController(createSourceConfiguration(0))
    f(ctrl, ev)
    verifyZeroInteractions(ev)
  }

  it should "ignore de-iconified events" in {
    checkIgnoredEvent(_.windowDeiconified(_))
  }

  it should "ignore closing events" in {
    checkIgnoredEvent(_.windowClosing(_))
  }

  it should "ignore closed events" in {
    checkIgnoredEvent(_.windowClosed(_))
  }

  it should "ignore activated events" in {
    checkIgnoredEvent(_.windowActivated(_))
  }

  it should "ignore deactivated events" in {
    checkIgnoredEvent(_.windowDeactivated(_))
  }

  it should "ignore iconified events" in {
    checkIgnoredEvent(_.windowIconified(_))
  }

  it should "allow starting playback" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createController(createSourceConfiguration(0))

    ctrl.startPlayback()
    helper.verifyStartPlayback().verifyAction(StartPlaybackAction, enabled = false)
      .verifyAction(StopPlaybackAction, enabled = true)
  }

  it should "allow stopping playback" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createController(createSourceConfiguration(0))

    ctrl.stopPlayback()
    helper.verifyStopPlayback().verifyAction(StartPlaybackAction, enabled = true)
      .verifyAction(StopPlaybackAction, enabled = false)
  }

  /**
    * A helper class managing the dependencies of the test object.
    */
  private class RadioControllerTestHelper {
    /** Mock for the radio player. */
    val player = mock[RadioPlayer]

    /** Mock for the application context. */
    val applicationContext = mock[ApplicationContext]

    /** Mock for the combo box handler. */
    val comboHandler = createComboHandlerMock()

    /** Mock for the status line handler. */
    val statusHandler = mock[StaticTextHandler]

    /** Mock for the handler for the playback time. */
    val playbackTimeHandler = mock[StaticTextHandler]

    /** A map with mock actions. */
    private val actions = createActionMap()

    /** Mock for the action store. */
    val actionStore = createActionStore(actions)

    /**
      * Creates a test instance of a radio controller.
      *
      * @param srcConfig     the configuration for the radio sources
      * @param configuration the configuration
      * @return the test instance
      */
    def createController(srcConfig: RadioSourceConfig,
                         configuration: Configuration = new HierarchicalConfiguration):
    RadioController =
      new RadioController(player, configuration, applicationContext, actionStore, comboHandler,
        statusHandler, playbackTimeHandler, c => {
          c should be(configuration)
          srcConfig
        })

    /**
      * Creates a radio controller test instance and initializes it by sending
      * it a window opened event.
      *
      * @param srcConfig     the configuration for the radio sources
      * @param configuration the configuration
      * @return the test instance
      */
    def createInitializedController(srcConfig: RadioSourceConfig,
                                    configuration: Configuration = new HierarchicalConfiguration)
    : RadioController = {
      val ctrl = createController(srcConfig, configuration)
      ctrl windowOpened event()
      ctrl
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
      * @return this test helper
      */
    def verifyAction(name: String, enabled: Boolean): RadioControllerTestHelper = {
      verify(actions(name)).setEnabled(enabled)
      this
    }

    /**
      * Verifies that the player was triggered to switch to the specified
      * source.
      *
      * @param src the source
      * @return this test helper
      */
    def verifySwitchSource(src: RadioSource, delay: FiniteDuration = 5.seconds):
    RadioControllerTestHelper = {
      verify(player).switchToSource(src, delay)
      this
    }

    /**
      * Verifies that playback was started.
      *
      * @return this test helper
      */
    def verifyStartPlayback(): RadioControllerTestHelper = {
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
