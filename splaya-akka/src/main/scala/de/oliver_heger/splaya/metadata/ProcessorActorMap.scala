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
 * A trait implementing basic functionality for mapping items to the paths of
 * currently processed media files during meta data generation.
 *
 * While extracting meta data from media files, for each media file a couple
 * of helper objects are needed. These are created on demand and then cached
 * until processing of the associated file is done. The major part of this
 * functionality is implemented by this trait. Concrete sub classes just have
 * to define the creation of the managed objects.
 *
 * @tparam V the type of items managed by the represented map
 */
trait PathItemMap[V] {
  /** A map for storing the items managed by this object.*/
  private val itemMap = collection.mutable.Map.empty[Path, V]

  /**
   * Removes the assignment for the given path from this map and returns an
   * option for the associated item. This method can be called when the
   * processing of the media file with this path is done and the associated
   * item is not longer needed. If already created, the item is returned so
   * that it can be gracefully shutdown if needed. Otherwise, result is
   * ''None''.
   * @param path the path in question
   * @return an option for the assigned item
   */
  def removeItemFor(path: Path): Option[V] = itemMap remove path

  /**
   * Returns the managed item associated with the given path. If this path is
   * accessed for the first time, the item is created using the specified
   * creator operation.
   * @param path the path
   * @param itemCreator the operation for creating the item
   * @return the item assigned to this path
   */
  protected def getOrCreateItem(path: Path, itemCreator: => V): V = {
    itemMap.getOrElseUpdate(path, itemCreator)
  }
}

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
private class ProcessorActorMap(val creationProps: Props) extends PathItemMap[ActorRef] {
  /**
   * Returns the reference to a child actor associated with the given path. If
   * no such child actor exists, it is created now using the provided
   * ''ChildActorFactory''.
   * @param path the path to be processed by the actor
   * @param factory the ''ChildActorFactory''
   * @return the child actor assigned to this path
   */
  def getOrCreateActorFor(path: Path, factory: ChildActorFactory): ActorRef = {
    getOrCreateItem(path, factory createChildActor creationProps)
  }
}
