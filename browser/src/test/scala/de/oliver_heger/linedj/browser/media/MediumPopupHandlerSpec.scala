/*
 * Copyright 2015-2019 The Developers Team.
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

import net.sf.jguiraffe.di.BeanContext
import net.sf.jguiraffe.gui.builder.action.{ActionStore, FormAction, PopupMenuBuilder}
import net.sf.jguiraffe.gui.builder.components.ComponentBuilderData
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
 * Test class for ''MediumPopupHandler''.
 */
class MediumPopupHandlerSpec extends FlatSpec with Matchers with MockitoSugar {
  "A MediumPopupHandler" should "add actions for appending medium, artist, and album" in {
    val helper = new MediumPopupHandlerTestHelper

    helper.invokeHandler().verifyActionAdded(helper.actAddMedium).verifyActionAdded(helper
      .actAddArtists).verifyActionAdded(helper.actAddAlbums)
  }

  it should "add an action for the currently selected songs" in {
    val helper = new MediumPopupHandlerTestHelper
    val actAddSongs = helper prepareAddSongsAction true

    helper.invokeHandler().verifyActionAdded(actAddSongs)
  }

  it should "not add an action for selected songs if there is no selection" in {
    val helper = new MediumPopupHandlerTestHelper
    val actAddSongs = helper prepareAddSongsAction false

    helper.invokeHandler().verifyActionNotAdded(actAddSongs)
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

    /** A mock for the action store. */
    val actionStore = createActionStore()

    /** A mock for the popup menu builder. */
    private val popupBuilder = createPopupBuilder()

    /** The popup handler to be tested. */
    private val popupHandler = new MediumPopupHandler

    /**
      * Prepares the action for adding songs. The enabled flag is set.
      * @param enabled the enabled flag
      * @return the action for addings songs
      */
    def prepareAddSongsAction(enabled: Boolean): FormAction = {
      when(actAddSongs.isEnabled).thenReturn(enabled)
      actAddSongs
    }

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
     * Invokes the test handler.
     * @return this object
     */
    def invokeHandler(): MediumPopupHandlerTestHelper = {
      popupHandler.constructPopup(popupBuilder, createComponentData())
      verify(popupBuilder).create()
      this
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
