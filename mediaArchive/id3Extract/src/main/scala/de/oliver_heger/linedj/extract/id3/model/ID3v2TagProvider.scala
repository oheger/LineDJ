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

package de.oliver_heger.linedj.extract.id3.model

import de.oliver_heger.linedj.extract.metadata.MetaDataProvider

/**
  * An implementation of the ''ID3TagProvider'' trait based on an ID3v2 frame.
  *
  * This class is passed a sequence with tag names and an underlying
  * [[ID3Frame]] object at construction time. The access methods for specific
  * tags defined by the ''MetaDataProvider'' trait are simply mapped to tag
  * names in the sequence.
  *
  * Note: This class is used internally only, therefore it does not implement
  * sophisticated parameter checks.
  *
  * @param frame    the underlying ID3v2 frame
  * @param tagNames the tag names corresponding to the specific access methods
  */
private class ID3v2TagProvider(val frame: ID3Frame, tagNames: IndexedSeq[String])
  extends MetaDataProvider {
  def title: Option[String] = get(0)

  def artist: Option[String] = get(1)

  def album: Option[String] = get(2)

  def inceptionYearString: Option[String] = get(3)

  def trackNoString: Option[String] = get(4)

  /**
    * Obtains the value of the tag with the given index in the array of tag
    * names.
    *
    * @param idx the index
    * @return an option with the value of this tag
    */
  private def get(idx: Int): Option[String] =
    frame.tags.get(tagNames(idx)) map (_.asString)
}
