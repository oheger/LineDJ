/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.platform

import net.sf.jguiraffe.gui.builder.action.{ActionStore, FormAction}
import org.mockito.ArgumentMatchers.{anyBoolean, anyString}
import org.mockito.Mockito.{doAnswer, when}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatestplus.mockito.MockitoSugar

/**
  * A test helper trait that simplifies usage of of ''FormAction'' objects.
  *
  * Using this trait makes it easy to create mock actions that are able to
  * record their enabled state. It is also possible to create a mock
  * ''ActionStore'' that allows access to all mock actions.
  */
trait ActionTestHelper {
  this: MockitoSugar =>

  /** A map with mock actions to be managed by this instance. */
  var actions = Map.empty[String, FormAction]

  /** A map with the current action enabled states. */
  private var actionStates = Map.empty[String, Boolean]

  /**
    * Creates a mock action that is able to record its enabled state. The
    * action is stored in the map managed by this trait.
    *
    * @param name the name of the action
    * @return the mock action
    */
  def createAction(name: String): FormAction = {
    val action = mock[FormAction]
    doAnswer(new Answer[AnyRef] {
      override def answer(invocation: InvocationOnMock): AnyRef = {
        val enabled = invocation.getArguments.head.asInstanceOf[Boolean]
        actionStates += (name -> enabled)
        null
      }
    }).when(action).setEnabled(anyBoolean())
    actionStates += (name -> true)
    actions += (name -> action)
    action
  }

  /**
    * Returns the enabled state of the specified action. Note that this action
    * must exist.
    *
    * @param name the action name
    * @return the enabled state for this action
    */
  def isActionEnabled(name: String): Boolean = actionStates(name)

  /**
    * Convenience function for creating multiple actions at once. Invokes
    * ''createAction()'' for each of the specified names.
    *
    * @param names the names of the actions to be created
    */
  def createActions(names: String*): Unit = {
    names foreach createAction
  }

  /**
    * Resets the enabled states of all managed action mocks to the specified
    * state.
    *
    * @param enabled the new enabled state
    */
  def resetActionStates(enabled: Boolean = false): Unit = {
    actionStates = actionStates map (t => (t._1, enabled))
  }

  /**
    * Creates a mock action store object that allows access to the actions
    * created by this instance.
    *
    * @return the mock action store
    */
  def createActionStore(): ActionStore = {
    val store = mock[ActionStore]
    when(store.getAction(anyString())).thenAnswer(new Answer[FormAction] {
      override def answer(invocation: InvocationOnMock): FormAction =
        actions(invocation.getArguments.head.asInstanceOf[String])
    })
    store
  }
}
