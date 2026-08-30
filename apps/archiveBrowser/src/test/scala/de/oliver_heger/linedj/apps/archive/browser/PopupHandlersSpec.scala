/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.apps.archive.browser

import de.oliver_heger.linedj.platform.ActionTestHelper
import net.sf.jguiraffe.di.BeanContext
import net.sf.jguiraffe.gui.builder.action.{ActionBuilder, ActionStore, PopupMenuBuilder, PopupMenuHandler}
import net.sf.jguiraffe.gui.builder.components.ComponentBuilderData
import org.mockito.Mockito.*
import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for the popup handler implementations.
  */
class PopupHandlersSpec extends AnyFlatSpec, Matchers:
  "An ArtistPopupHandler" should "add all artist-related actions" in :
    val helper = new PopupHandlerTestHelper

    helper.invokeHandler(new ArtistPopupHandler)
      .verifyActionsAdded(
        expectSeparator = true,
        SelectionChangeHandler.AddMediumAction,
        SelectionChangeHandler.AddArtistAction,
        SelectionChangeHandler.AddArtistAlbumAction,
        SelectionChangeHandler.AddArtistAlbumSongsAction
      )

  it should "only add the add songs action if it is enabled" in :
    val helper = new PopupHandlerTestHelper

    helper.disableAction(SelectionChangeHandler.AddArtistAlbumSongsAction)
      .invokeHandler(new ArtistPopupHandler)
      .verifyActionsAdded(
        expectSeparator = false,
        SelectionChangeHandler.AddMediumAction,
        SelectionChangeHandler.AddArtistAction,
        SelectionChangeHandler.AddArtistAlbumAction
      )

  /**
    * A test helper class managing the actions and an action store and the
    * required objects to invoke a popup handler.
    */
  private class PopupHandlerTestHelper extends ActionTestHelper, MockitoSugar:
    /** The action store to be accessed by the popup handler. */
    private val actionStore = createInitializedActionStore()

    /** The mock popup menu builder. */
    private val popupBuilder = mock[PopupMenuBuilder]

    /**
      * Disables the action with the given name.
      *
      * @param name the action name
      * @return this test helper
      */
    def disableAction(name: String): PopupHandlerTestHelper =
      actionStore.getAction(name).setEnabled(false)
      this

    /**
      * Invokes the given handler object and records the interaction with the
      * popup menu builder, so that it can be verified later.
      *
      * @param handler the handler to invoke
      * @return this test helper
      */
    def invokeHandler(handler: PopupMenuHandler): PopupHandlerTestHelper =
      val builderData = mock[ComponentBuilderData]
      val beanContext = mock[BeanContext]
      doReturn(actionStore).when(beanContext).getBean(ActionBuilder.KEY_ACTION_STORE)
      doReturn(beanContext).when(builderData).getBeanContext
      handler.constructPopup(popupBuilder, builderData)
      this

    /**
      * Verifies that all the provided actions have been added to the popup
      * menu - and only those.
      *
      * @param expectSeparator flag whether a separator is expected
      * @param actions         the expected actions
      * @return this test helper
      */
    def verifyActionsAdded(expectSeparator: Boolean, actions: String*): PopupHandlerTestHelper =
      forEvery(actions): actionName =>
        val action = actionStore.getAction(actionName)
        verify(popupBuilder).addAction(action)
      if expectSeparator then
        verify(popupBuilder).addSeparator()
      verify(popupBuilder).create()
      verifyNoMoreInteractions(popupBuilder)
      this

    /**
      * Creates the action store with the relevant actions. Per default, all
      * actions are enabled.
      *
      * @return the action store
      */
    private def createInitializedActionStore(): ActionStore =
      createActions(
        SelectionChangeHandler.AddAlbumAction,
        SelectionChangeHandler.AddAlbumSongsAction,
        SelectionChangeHandler.AddArtistAction,
        SelectionChangeHandler.AddArtistAlbumAction,
        SelectionChangeHandler.AddArtistAlbumSongsAction,
        SelectionChangeHandler.AddMediumAction
      )
      createActionStore()
