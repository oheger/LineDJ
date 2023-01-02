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

package de.oliver_heger.linedj.shared.ser

import akka.serialization.Serializer
import akka.util.ByteString
import de.oliver_heger.linedj.shared.archive.media.DownloadDataResult

/**
  * A special ''Serializer'' implementation for the [[DownloadDataResult]]
  * message type.
  *
  * This type cannot be handled by the default Jackson serializer; therefore,
  * we provide a custom serializer, which can handle the ''ByteString'' in the
  * message effectively.
  */
class DownloadDataResultSerializer extends Serializer {
  override def identifier: Int = 10001

  override def includeManifest: Boolean = false

  /**
    * This implementation can only handle ''DownloadDataResult'' objects.
    * Their binary representation consists of the content of their
    * ''ByteString'' property.
    *
    * @param o the object to be serialized
    * @return the serialized representation of the object
    */
  override def toBinary(o: AnyRef): Array[Byte] =
    o.asInstanceOf[DownloadDataResult].data.toArray

  /**
    * This implementation creates a new ''DownloadDataResult'' object with the
    * data from the given array.
    *
    * @param bytes    the binary object representation
    * @param manifest the manifest (ignored)
    * @return the newly created instance
    */
  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef =
    DownloadDataResult(ByteString(bytes))
}
