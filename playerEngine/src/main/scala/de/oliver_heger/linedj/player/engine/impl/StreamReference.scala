/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.impl

import java.io.InputStream
import java.net.URL

/**
  * A class referencing a stream to be opened. The stream is identified by an
  * URI. The class offers a method for opening it through the
  * ''java.net.URL'' class.
  *
  * @param uri the URI of the referenced stream
  */
private case class StreamReference(uri: String) {
  /**
    * Opens the referenced stream from the URI stored in this class.
    *
    * @return the ''InputStream'' referenced by this object
    * @throws java.io.IOException if an error occurs
    */
  @scala.throws[java.io.IOException] def openStream(): InputStream =
    new URL(uri).openStream()
}
