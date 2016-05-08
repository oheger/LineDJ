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
import net.sf.jguiraffe.gui.builder.action.{ActionStore, FormAction}
import net.sf.jguiraffe.gui.builder.components.model.{ListComponentHandler, ListModel}
import net.sf.jguiraffe.gui.builder.event.FormChangeEvent
import net.sf.jguiraffe.gui.builder.window.WindowEvent
import org.apache.commons.configuration.{Configuration, HierarchicalConfiguration}
import org.mockito.Mockito
import org.mockito.Mockito._
import org.mockito.Matchers._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

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
    * Determines whether the radio source with the given index has a default
    * file extension. All sources with an odd index have.
    *
    * @param idx the index
    * @return a flag whether this source has a default extension
    */
  private def hasDefaultExt(idx: Int): Boolean = idx % 2 != 0

  /**
    * Generates a radio source object based on the given index.
    *
    * @param idx the index
    * @return the radio source for this index
    */
  private def radioSource(idx: Int): RadioSource =
    RadioSource(RadioSourceURI + idx, if (hasDefaultExt(idx)) Some("mp3") else None)

  /**
    * Creates a configuration that contains the given number of radio sources.
    * All sources are defined with their name and URI. Sources with an odd
    * index have a default file extension.
    *
    * @param count the number of radio sources
    * @return the configuration defining the number of radio sources
    */
  private def createSourceConfiguration(count: Int): Configuration = {
    val sources = (1 to count) map (i => (sourceName(i), radioSource(i)))
    createSourceConfiguration(sources)
  }

  /**
    * Creates a configuration that contains the specified radio sources.
    *
    * @param sources a list with the sources to be contained
    * @return the configuration with these radio sources
    */
  private def createSourceConfiguration(sources: Seq[(String, RadioSource)]): Configuration = {
    val config = new HierarchicalConfiguration
    sources foreach { t =>
      config.addProperty("radio.sources.source(-1).name", t._1)
      config.addProperty("radio.sources.source.uri", t._2.uri)
      t._2.defaultExtension foreach (config.addProperty("radio.sources.source.extension", _))
    }
    config
  }
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

  "A RadioController" should "add radio sources to the combo box" in {
    val helper = new RadioControllerTestHelper
    helper.createInitializedController(createSourceConfiguration(4))

    helper.verifySourcesAddedToCombo(1, 2, 3, 4).verifySelectedSource(1)
      .verifyNoMoreInteractionWithCombo()
  }

  it should "filter out incomplete source configuration entries" in {
    val sources = List((sourceName(1), radioSource(1)), (sourceName(2), RadioSource(null)),
      (sourceName(3), radioSource(3)))
    val config = createSourceConfiguration(sources)
    config.addProperty("radio.sources.source(-1).uri", "someURI")
    val helper = new RadioControllerTestHelper

    helper.createInitializedController(config)
    helper.verifySourcesAddedToCombo(1, 3).verifySelectedSource(1)
      .verifyNoMoreInteractionWithCombo()
  }

  it should "order radio sources by name" in {
    val sources = List((sourceName(8), radioSource(8)), (sourceName(2), radioSource(2)),
      (sourceName(3), radioSource(3)), (sourceName(9), radioSource(9)))
    val helper = new RadioControllerTestHelper

    helper.createInitializedController(createSourceConfiguration(sources))
    helper.verifySourcesAddedToCombo(2, 3, 8, 9).verifySelectedSource(2)
      .verifyNoMoreInteractionWithCombo()
  }

  it should "handle missing source configurations correctly" in {
    val helper = new RadioControllerTestHelper

    helper.createInitializedController(new HierarchicalConfiguration)
    helper.verifySourcesAddedToCombo().verifyNoMoreInteractionWithCombo()
  }

  it should "disable playback actions if there are no sources" in {
    val helper = new RadioControllerTestHelper
    helper.createInitializedController(new HierarchicalConfiguration)

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
      .verifyNoMoreInteractionWithPlayer()
  }

  it should "start playback with the stored source from the configuration" in {
    val config = createSourceConfiguration(4)
    config.addProperty("radio.current", sourceName(2))
    val helper = new RadioControllerTestHelper
    helper.createInitializedController(config)

    helper.verifySelectedSource(2).verifySwitchSource(radioSource(2)).verifyStartPlayback()
      .verifyNoMoreInteractionWithPlayer()
  }

  it should "store the current source in the configuration after startup" in {
    val helper = new RadioControllerTestHelper
    val config = createSourceConfiguration(1)
    helper.createInitializedController(config)

    config getString "radio.current" should be(sourceName(1))
  }

  it should "ignore the stored source if it cannot be resolved" in {
    val config = createSourceConfiguration(3)
    config.addProperty("radio.current", sourceName(4))
    val helper = new RadioControllerTestHelper
    helper.createInitializedController(config)

    helper.verifySwitchSource(radioSource(1)).verifyStartPlayback()
      .verifyNoMoreInteractionWithPlayer()
  }

  it should "react on changes in the radio sources selection" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createController(new HierarchicalConfiguration)
    val src = radioSource(2)
    doReturn(src).when(helper.comboHandler).getData

    ctrl elementChanged mock[FormChangeEvent]
    helper.verifySwitchSource(radioSource(2))
  }

  it should "update the current source in the config after a change in the combo selection" in {
    val config = createSourceConfiguration(3)
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(config)
    val src = radioSource(2)
    doReturn(src).when(helper.comboHandler).getData

    ctrl elementChanged mock[FormChangeEvent]
    config getString "radio.current" should be(sourceName(2))
  }

  it should "handle an empty selection in the sources combo box" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createController(new HierarchicalConfiguration)
    doReturn(null).when(helper.comboHandler).getData

    ctrl elementChanged mock[FormChangeEvent]
    helper.verifyNoMoreInteractionWithPlayer()
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
      .verifyNoMoreInteractionWithPlayer()
  }

  it should "reset the sources update flag after the update" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createInitializedController(createSourceConfiguration(4))
    helper verifySwitchSource radioSource(1)
    val selectedSource = radioSource(2)

    doReturn(selectedSource).when(helper.comboHandler).getData
    ctrl.elementChanged(null)
    helper verifySwitchSource selectedSource
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
    val ctrl = helper.createController(new HierarchicalConfiguration)
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
    val ctrl = helper.createController(new HierarchicalConfiguration)

    ctrl.startPlayback()
    helper.verifyStartPlayback().verifyAction(StartPlaybackAction, enabled = false)
      .verifyAction(StopPlaybackAction, enabled = true)
  }

  it should "allow stopping playback" in {
    val helper = new RadioControllerTestHelper
    val ctrl = helper.createController(new HierarchicalConfiguration)

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

    /** Mock for the combo box handler. */
    val comboHandler = createComboHandlerMock()

    /** A map with mock actions. */
    private val actions = createActionMap()

    /** Mock for the action store. */
    val actionStore = createActionStore(actions)

    /**
      * Creates a test instance of a radio controller.
      *
      * @param configuration the configuration
      * @return the test instance
      */
    def createController(configuration: Configuration): RadioController =
      new RadioController(player, configuration, actionStore, comboHandler)

    /**
      * Creates a radio controller test instance and initializes it by sending
      * it a window opened event.
      *
      * @param configuration the configuration
      * @return the test instance
      */
    def createInitializedController(configuration: Configuration): RadioController = {
      val ctrl = createController(configuration)
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
    def verifySwitchSource(src: RadioSource): RadioControllerTestHelper = {
      verify(player).switchToSource(src)
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
      * Verifies that further interaction occurred with the player.
      *
      * @return this test helper
      */
    def verifyNoMoreInteractionWithPlayer(): RadioControllerTestHelper = {
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
      * @param actioMap a map with supported actions
      * @return the mock action store
      */
    private def createActionStore(actioMap: Map[String, FormAction]): ActionStore = {
      val store = mock[ActionStore]
      actioMap foreach { e =>
        when(store.getAction(e._1)).thenReturn(e._2)
      }
      store
    }
  }

}
