/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import org.mockito.ArgumentCaptor
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import scalaz.State

/**
  * A trait that supports test cases for objects using the state monad.
  *
  * This helper trait manages a mock for an update service for a given state
  * type. It offers functionality for mocking methods of this service and to
  * keep track on the state objects that were passed to service functions.
  *
  * @tparam ServiceState  the type of the state managed by the service
  * @tparam UpdateService the type of the state management service
  */
trait StateTestHelper[ServiceState, UpdateService]:
  /**
    * The mock for the state update service to be used by tests. Concrete
    * implementations must create the correct service mock.
    */
  val updateService: UpdateService

  /**
    * A queue which stores the state objects passed to the state monad
    * returned by the update service.
    */
  private val stateQueue = new LinkedBlockingQueue[ServiceState]

  /**
    * Prepares the mock update service to expect an invocation that returns
    * a ''State'' with the specified parameters.
    *
    * @param data  the additional data
    * @param state the updated sink state
    * @param f     a function that invokes the mock update service
    * @tparam A the type of the additional data
    * @return this test helper
    */
  def stub[A](data: A, state: ServiceState)
             (f: UpdateService => State[ServiceState, A]): this.type =
    when(f(updateService)).thenAnswer((_: InvocationOnMock) => createState(state, data))
    this

  /**
    * Invokes the given function on the update service to capture an
    * argument.
    *
    * @param f the function to be invoked
    * @tparam A the type of the result
    * @return the captured argument
    */
  def capture[A](f: UpdateService => ArgumentCaptor[A]): A =
    f(updateService).getValue

  /**
    * Returns the next state that was passed to an update operation if
    * available.
    *
    * @return an option for the next state
    */
  def nextUpdatedState(): Option[ServiceState] =
    Option(stateQueue.poll(3, TimeUnit.SECONDS))

  /**
    * Expects a state transition from the passed in state.
    *
    * @param state the expected (original) state
    * @return this test helper
    */
  def expectStateUpdate(state: ServiceState): this.type =
    val optState = nextUpdatedState()
    if !optState.contains(state) then
      throw new AssertionError(s"Wrong state update. Expected $state, was $optState")
    this

  /**
    * Creates a ''State'' object that records the passed in former state and
    * returns the specified data.
    *
    * @param state the updated state
    * @param data  the additional data to be returned
    * @tparam A the type of the additional data
    * @return the ''State'' object
    */
  protected def createState[A](state: ServiceState, data: A): State[ServiceState, A] =
    State { s =>
      stateQueue offer s
      (state, data)
    }
