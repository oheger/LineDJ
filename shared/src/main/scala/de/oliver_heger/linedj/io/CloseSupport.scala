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

package de.oliver_heger.linedj.io

import akka.actor.{ActorRef, Props}
import de.oliver_heger.linedj.io.CloseHandlerActor.ConditionSatisfied
import de.oliver_heger.linedj.utils.ChildActorFactory

/**
  * A trait providing support for complex close handling.
  *
  * Some actors - and not only file-related ones - react on [[CloseRequest]]
  * messages to initiate a graceful shutdown or to cancel an ongoing operation.
  * The protocol requires that a [[CloseAck]] message is sent when the close
  * operation is complete.
  *
  * If only a single actor is involved, the implementation of close handling is
  * typically straight-forward. If, however, an actor depends on multiple other
  * actors which have to be closed first, situation becomes complicated
  * quickly. Then close requests have to be propagated to all dependent actors,
  * and the ''CloseAck'' messages from all of them have to be waited for.
  *
  * This trait implements a major of the functionality of such a complex close
  * handling. It offers two methods:
  *
  *  - ''onCloseRequest()'' has to be called whenever the current actor
  * receives a ''CloseRequest'' message. The method is passed the dependent
  * actors to be closed and also a child actor factory, so that required
  * helper actors can be created. These helper actors make sure that all
  * dependent actors are notified and track their responses. An additional
  * condition is supported via a boolean flag which can be passed to the
  * method; if set to '''false''', the close operation cannot complete before
  * this condition is satisfied, which is triggered by an invocation of the
  * ''onConditionSatisfied()'' method.
  *
  *  - When close handling is done the triggering actor receives a
  * [[de.oliver_heger.linedj.io.CloseHandlerActor.CloseComplete]] message. In
  * reaction on this method, it should invoke the ''onCloseComplete()'' method.
  * This method resets the state, so that further close requests can be
  * handled. (This is necessary only if ''CloseRequest'' is used to abort a
  * running operation; if the message causes the actor to shutdown, it does not
  * matter whether the state is reset.)
  */
trait CloseSupport {
  /** Stores the handler actor for the current close operation. */
  private var currentHandler: Option[ActorRef] = None

  /**
    * Returns a flag whether a close operation is currently in progress. This
    * method returns '''true''' from the first invocation of
    * ''onCloseRequest()'', until the operation is done.
    *
    * @return a flag whether a close request is currently in progress
    */
  def isCloseRequestInProgress: Boolean = currentHandler.isDefined

  /**
    * Starts handling of a close request.
    *
    * @param subject the subject of the request, i.e. the actor to be closed
    * @param deps    the dependencies of the subject actor; these actors must be
    *                closed first before the subject can be closed
    * @param target  the target actor expecting the ''CloseAck''
    * @param factory a factory for creating actors
    * @return '''true''' if a close operation is newly triggered; '''false'''
    *        if a close operation is already in progress
    */
  def onCloseRequest(subject: ActorRef, deps: => Iterable[ActorRef],
                     target: ActorRef, factory: ChildActorFactory,
                     conditionState: => Boolean = true): Boolean = {
    val triggerOperation = currentHandler.isEmpty
    if (triggerOperation)
      currentHandler = Some(factory.createChildActor(Props(classOf[CloseHandlerActor],
        subject, deps, conditionState)))
    factory.createChildActor(Props(classOf[CloseNotifyActor], currentHandler.get,
      subject, target))
    triggerOperation
  }

  /**
    * Notifies this object that the close operation is complete.
    */
  def onCloseComplete(): Unit = {
    currentHandler = None
  }

  /**
    * Notifies this object that an additional condition is satisfied. The close
    * operation can now be completed if all pending ''CloseAck'' messages have
    * been received.
    */
  def onConditionSatisfied(): Unit = {
    currentHandler foreach(_ ! ConditionSatisfied)
  }
}
