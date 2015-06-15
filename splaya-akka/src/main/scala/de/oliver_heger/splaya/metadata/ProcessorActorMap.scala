/*
 * Copyright 2015 The Developers Team.
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
package de.oliver_heger.splaya.metadata

import java.nio.file.Path

import akka.actor.{ActorRef, Props}
import de.oliver_heger.splaya.utils.ChildActorFactory

/**
 * An internally used helper class for storing temporary instances of actors
 * used for processing meta data.
 *
 * During the processing of media files, multiple processing steps are
 * executed. This is done by specialized actors. It is not clear from the
 * beginning how many specialized actors are needed because all processing is
 * done in parallel. Therefore, actors are created on demand and stopped when
 * they are done.
 *
 * This helper class is responsible for managing processor actors of a
 * specific type. When the processing of a new media file starts the map is
 * asked for a corresponding actor. It is created dynamically. When later more
 * data of the media file is to be processed the actor instance is already
 * available and is reused. When processing of the file is done the actor is
 * removed again.
 *
 * @param creationProps ''Props'' for the actor instances to be created
 */
private class ProcessorActorMap(val creationProps: Props) {
  /** A map for storing the actors associated with paths. */
  private val actorMap = collection.mutable.Map.empty[Path, ActorRef]

  /**
   * Returns the reference to a child actor associated with the given path. If
   * no such child actor exists, it is created now using the provided
   * ''ChildActorFactory''.
   * @param path the path to be processed by the actor
   * @param factory the ''ChildActorFactory''
   * @return the child actor assigned to this path
   */
  def getOrCreateActorFor(path: Path, factory: ChildActorFactory): ActorRef = {
    actorMap.getOrElseUpdate(path, factory createChildActor creationProps)
  }

  /**
   * Removes the assignment for the given path from this map and returns an
   * option for the associated actor. This method can be called when the
   * processing of the path is done and the associated child actor no longer
   * needs to be invoked.
   * @param path the path in question
   * @return an option for the assigned child actor
   */
  def removeActorFor(path: Path): Option[ActorRef] = actorMap remove path
}
