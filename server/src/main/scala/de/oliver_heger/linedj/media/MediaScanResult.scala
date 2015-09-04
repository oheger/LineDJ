/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.media

import java.nio.file.Path

/**
 * A data class representing a file on a medium.
 *
 * The file is uniquely identified by the ''Path'' to the data on disk. In
 * addition, some meta data is provided.
 *
 * @param path the path to the represented file
 * @param size the size of the file
 */
case class MediaFile(path: Path, size: Long)

/**
 * A data class storing the results of a directory scan for media files (in its
 * raw form).
 *
 * This class consists of a map with ''MediumID'' objects and the files of this
 * medium assigned to it. The medium ID can be used to obtain a path pointing
 * to the corresponding medium description file if available.
 *
 * @param root the root path that has been scanned
 * @param mediaFiles a map with files assigned to a medium
 */
case class MediaScanResult(root: Path, mediaFiles: Map[MediumID, List[MediaFile]])
