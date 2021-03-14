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

package de.oliver_heger.linedj.platform.mediaifc

import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.shared.archive.media.MediumID
import scalaz.Kleisli

import scala.concurrent.Future

/**
  * Service interface for accessing media and their meta data.
  *
  * This service provides operations for querying meta data for media that
  * make use of a future-like API. The underlying interaction via the message
  * bus is hidden for clients.
  *
  * @tparam Media    data type for the available media
  * @tparam MetaData type for a chunk of meta data
  */
trait MetaDataService[Media, MetaData] {
  /**
    * Type definition for service results. The service returns functions that
    * expect a ''MessageBus'' to be passed in and return futures.
    */
  type MetaDataResult[T] = Kleisli[Future, MessageBus, T]

  /**
    * Returns a function that yields information about the available media when
    * passed a message bus instance.
    *
    * @return a function to retrieve the available media
    */
  def fetchMedia(): MetaDataResult[Media]

  /**
    * Returns a function that yields all the meta data of a specific medium
    * when passed a message bus instance.
    *
    * @param mediumID the ID of the medium desired
    * @return a function to retrieve the meta data of a medium
    */
  def fetchMetaDataOfMedium(mediumID: MediumID): MetaDataResult[MetaData]
}
