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

package de.oliver_heger.linedj.shared.archive.media

/**
 * A class defining meta data of a medium.
 *
 * This data class defines some properties with information about a medium. The
 * data which can be queried from an instance can be used e.g. in a UI when
 * presenting an overview over the media currently available.
 *
 * @param name the name of the represented medium
 * @param description an additional description about the represented medium
 * @param mediumID the ID of the represented medium
 * @param orderMode the default ordering mode for a playlist for this medium
 * @param orderParams additional parameters for sorting the medium; this is a
 *                    plain string whose interpretation depends on the order
 *                    mode
 * @param checksum an alphanumeric checksum calculated for this medium
 */
case class MediumInfo(name: String, description: String, mediumID: MediumID, orderMode: String,
                      orderParams: String, checksum: String)
