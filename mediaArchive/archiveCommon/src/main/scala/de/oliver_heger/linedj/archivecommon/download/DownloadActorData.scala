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

package de.oliver_heger.linedj.archivecommon.download

import java.util.concurrent.TimeUnit

import akka.actor.ActorRef

import scala.concurrent.duration.{Duration, FiniteDuration}

/**
 * An internally used helper class for storing information about actors used
 * for downloading media data to clients.
 *
 * Clients can request reader actors for loading the content of media files.
 * They are then responsible for stopping these actors when they are done. This
 * is a risk because client code may forget to stop an actor or crash before
 * such cleanup can be done.
 *
 * This class stores information related to download actors. It implements a
 * mapping from download actors to the client actors they have been propagated
 * to. Additionally, for each actor a timestamp is stored. It is then possible
 * to check in regular intervals for actors that are timed out - which likely
 * indicates the crash of a client. These actors can then be stopped by the
 * archive.
 */
class DownloadActorData {
  /** A mapping from processing actors to the client actors that read data. */
  private val clientMapping = collection.mutable.Map.empty[ActorRef, ActorRef]

  /** A map storing the last update time for a reader actor. */
  private val timestamps = collection.mutable.Map.empty[ActorRef, Long]

  /**
   * Adds the given mapping with its timestamp to this object. The mapping
   * associates an actor that have been passed to a client with its underlying
   * reader actor.
   * @param downloadActor the download actor to be managed
   * @param client the client actor of the read operation
   * @param timestamp the timestamp for this mapping
   * @return this object
   */
  def add(downloadActor: ActorRef, client: ActorRef, timestamp: Long):
  DownloadActorData = {
    clientMapping += downloadActor -> client
    timestamps += downloadActor -> timestamp
    this
  }

  /**
   * Checks whether information about the specified download actor is contained
   * in this object.
   * @param ref the actor reference to be checked
   * @return '''true''' if this reference is contained in this object;
   *         '''false''' otherwise
   */
  def hasActor(ref: ActorRef): Boolean = clientMapping contains ref

  /**
    * Finds all download actors that are associated with the given client
    * actor.
    * @param ref the client actor reference to be checked
    * @return an ''Iterable'' with the retrieved reader actors
    */
  def findReadersForClient(ref: ActorRef): Iterable[ActorRef] =
    clientMapping.filter(_._2 == ref).keys

  /**
    * Removes information about the given actor reference. Result is the
    * optional actor reference to the client actor. It is ''None'' if the
    * actor cannot be resolved.
    *
    * @param ref the actor reference that is to be removed
    * @return an ''Option'' with the associated client actor
    */
  def remove(ref: ActorRef): Option[ActorRef] = {
    timestamps remove ref
    clientMapping remove ref
  }

  /**
   * Determines all actor references for which a timeout occurred. These
   * references have been longer in this object without being updated than the
   * passed in duration. This indicates that the clients they belong to have
   * crashed.
   * @param time the current time
   * @param duration the maximum duration for mappings in this object
   * @return an ''Iterable'' with actor references with a timeout
   */
  def findTimeouts(time: Long, duration: FiniteDuration): Iterable[ActorRef] =
    (timestamps filter { e =>
      val stayTime = Duration(time - e._2, TimeUnit.MILLISECONDS)
      stayTime > duration
    }).keys

  /**
   * Updates the timestamp value of an actor in this mapping. This is used to
   * indicate that a client accessing this actor is still alive. Updating the
   * timestamp prevents an actor of being removed from the mapping too early.
   * If the actor is not contained in this mapping, this method has no effect.
   * @param ref the actor reference
   * @param timestamp the new timestamp for this actor
   * @return a flag whether the update was successful; '''false''' indicates
   *         that the actor was unknown
   */
  def updateTimestamp(ref: ActorRef, timestamp: Long): Boolean = {
    timestamps get ref match {
      case Some(_) =>
        timestamps += ref -> timestamp
        true

      case None =>
        false
    }
  }
}
