/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.browser.cache

import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.mediaifc.ext.MediaIfcExtension.{ConsumerFunction, ConsumerRegistration}
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.MetaDataChunk

/**
  * A message class processed by [[MetaDataCache]] to add a registration for the
  * meta data of a medium.
  *
  * With this message a component indicates its interest on the meta data of the
  * specified medium. Whenever meta data becomes available it is passed to the
  * callback function provided in the message.
  *
  * @param mediumID the ID of the medium
  * @param id       a unique ID to identify this listener; this can later be
  *                 used to remove the registration again
  * @param callback the callback function
  */
case class MetaDataRegistration(mediumID: MediumID, override val id: ComponentID,
                                override val callback: ConsumerFunction[MetaDataChunk])
  extends ConsumerRegistration[MetaDataChunk]

/**
 * A message class processed by [[MetaDataCache]] to remove the registration
 * for the meta data of a medium.
 *
 * When receiving a message of this type the cache will remove the represented
 * listener registration. This means that this listener will receive no further
 * messages for this medium.
 *
 * @param mediumID the ID of the medium
 * @param listenerID the unique listener ID
 */
case class RemoveMetaDataRegistration(mediumID: MediumID, listenerID: ComponentID)
