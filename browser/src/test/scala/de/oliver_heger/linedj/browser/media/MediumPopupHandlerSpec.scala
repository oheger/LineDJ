/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.browser.media

import java.util

import de.oliver_heger.linedj.browser.model.{AppendSongs, SongData}
import de.oliver_heger.linedj.client.remoting.MessageBus
import net.sf.jguiraffe.di.BeanContext
import net.sf.jguiraffe.gui.builder.action.{PopupMenuBuilder, ActionStore, FormAction}
import net.sf.jguiraffe.gui.builder.components.ComponentBuilderData
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import org.mockito.ArgumentCaptor
import org.mockito.Mockito._
import org.mockito.Matchers.any
import org.scalatest.mock.MockitoSugar
import org.scalatest.{Matchers, FlatSpec}

/**
 * Test class for ''MediumPopupHandler''.
 */
class MediumPopupHandlerSpec extends FlatSpec with Matchers with MockitoSugar {
  "A MediumPopupHandler" should "add an action for adding the current medium" in {
    val helper = new MediumPopupHandlerTestHelper

    helper.checkAction(helper.actAddMedium)(_.songsForSelectedMedium)
  }

  it should "add an action for adding the selected artists" in {
    val helper = new MediumPopupHandlerTestHelper

    helper.checkAction(helper.actAddArtists)(_.songsForSelectedArtists)
  }

  it should "add an action for adding the selected albums" in {
    val helper = new MediumPopupHandlerTestHelper

    helper.checkAction(helper.actAddAlbums)(_.songsForSelectedAlbums)
  }

  it should "add an action for the currently selected songs" in {
    val helper = new MediumPopupHandlerTestHelper
    val songs = List(mock[SongData], mock[SongData], mock[SongData], mock[SongData])
    val tableModel = util.Arrays.asList(null, songs.head, null, songs(1), songs(2), null, songs(3))
    doReturn(tableModel).when(helper.tableHandler).getModel
    when(helper.tableHandler.getSelectedIndices).thenReturn(Array(1, 3, 4, 6))

    helper.invokeHandler().verifyActionAdded(helper.actAddSongs).verifyActionTask(helper
      .actAddSongs, songs)
  }

  it should "not add an action for selected songs if there is no selection" in {
    val helper = new MediumPopupHandlerTestHelper

    helper.invokeHandler().verifyActionNotAdded(helper.actAddSongs)
  }

  /**
   * A test helper class collecting a bunch of mock objects required for most
   * of the tests.
   */
  private class MediumPopupHandlerTestHelper {
    /** The action for adding selected albums. */
    val actAddAlbums = action()

    /** The action for adding selected artists. */
    val actAddArtists = action()

    /** The action for adding the current medium. */
    val actAddMedium = action()

    /** The action for adding the selected songs. */
    val actAddSongs = action()

    /** A mock for the message bus. */
    val messageBus = mock[MessageBus]

    /** A mock for the action store. */
    val actionStore = createActionStore()

    /** A mock for the medium controller. */
    val controller = mock[MediaController]

    /** A mock for the table handler. */
    val tableHandler = createTableHandler()

    /** A mock for the popup menu builder. */
    private val popupBuilder = createPopupBuilder()

    /** The popup handler to be tested. */
    private val popupHandler = new MediumPopupHandler(messageBus)

    /**
     * Verifies that the specified action has been added to the popup builder.
     * @param action the action in question
     * @return this object
     */
    def verifyActionAdded(action: FormAction): MediumPopupHandlerTestHelper = {
      verify(popupBuilder).addAction(action)
      this
    }

    /**
     * Verifies that the specified action has not been added to the popup
     * builder.
     * @param action the action in question
     * @return this object
     */
    def verifyActionNotAdded(action: FormAction): MediumPopupHandlerTestHelper = {
      verify(popupBuilder, never()).addAction(action)
      this
    }

    /**
     * Prepares the mock controller to expect a request for selected songs. The
     * songs in question are selected by the passed in function.
     * @param r the function querying the songs from the controller
     * @return a list with test song data
     */
    def expectSelectionRequest(r: MediaController => Seq[SongData]): Seq[SongData] = {
      val songs = List(mock[SongData], mock[SongData], mock[SongData])
      when(r(controller)).thenReturn(songs)
      songs
    }

    /**
     * Invokes the test handler.
     * @return this object
     */
    def invokeHandler(): MediumPopupHandlerTestHelper = {
      popupHandler.constructPopup(popupBuilder, createComponentData())
      verify(popupBuilder).create()
      verifyZeroInteractions(controller)
      this
    }

    /**
     * Verifies that the task of the specified action produces the expected
     * results. This method checks whether the expected message with songs to
     * be added is published on the message bus.
     * @param action the action to be verified
     * @param expSongs the expected songs
     * @return this object
     */
    def verifyActionTask(action: FormAction, expSongs: Seq[SongData]):
    MediumPopupHandlerTestHelper = {
      val captorTask = ArgumentCaptor forClass classOf[Runnable]
      verify(action).setTask(captorTask.capture())
      captorTask.getValue.run()
      verify(messageBus).publish(AppendSongs(expSongs))
      this
    }

    /**
     * Executes a test whether an action is correctly added to the popup
     * builder with a correct action task.
     * @param action the action to be tested
     * @param r the function querying the expected songs from the controller
     */
    def checkAction(action: FormAction)(r: MediaController => Seq[SongData]): Unit = {
      val songs = expectSelectionRequest(r)
      invokeHandler().verifyActionAdded(action).verifyActionTask(action, songs)
    }

    /**
     * Creates a mock component builder data object. Here mainly the bean
     * context is relevant which allows access to the action store and the
     * medium controller.
     * @return the component builder data mock
     */
    def createComponentData(): ComponentBuilderData = {
      val builderData = mock[ComponentBuilderData]
      val context = mock[BeanContext]
      when(builderData.getBeanContext).thenReturn(context)
      doReturn(actionStore).when(context).getBean("ACTION_STORE")
      doReturn(controller).when(context).getBean("mediaController")
      doReturn(tableHandler).when(builderData).getComponentHandler("tableMedia")
      builderData
    }

    /**
     * Creates a mock action.
     * @return the mock action
     */
    private def action(): FormAction = mock[FormAction]

    /**
     * Creates a mock action stores and prepares it to return the test actions.
     * @return the action store mock
     */
    private def createActionStore(): ActionStore = {
      val store = mock[ActionStore]
      when(store.getAction("addAlbumAction")).thenReturn(actAddAlbums)
      when(store.getAction("addArtistAction")).thenReturn(actAddArtists)
      when(store.getAction("addMediumAction")).thenReturn(actAddMedium)
      when(store.getAction("addSongsAction")).thenReturn(actAddSongs)
      store
    }

    /**
     * Creates the mock for the popup menu builder.
     * @return the mock popup builder
     */
    private def createPopupBuilder(): PopupMenuBuilder = {
      val builder = mock[PopupMenuBuilder]
      when(builder.addAction(any(classOf[FormAction]))).thenReturn(builder)
      builder
    }

    /**
     * Creates a mock for the table handler.
     * @return the table handler mock
     */
    private def createTableHandler(): TableHandler = {
      val handler = mock[TableHandler]
      when(handler.getSelectedIndices).thenReturn(Array.empty[Int])
      handler
    }
  }

}
