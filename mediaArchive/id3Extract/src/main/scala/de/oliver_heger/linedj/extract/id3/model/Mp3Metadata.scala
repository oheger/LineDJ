/*
 * Copyright 2015-2025 The Developers Team.
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

/**
  * A data class storing metadata extracted from an MP3 audio file.
  *
  * An instance of this class is created based on the full content of an MP3
  * audio file. It contains all the results accumulated during file processing.
  *
  * @param version        the MPEG version
  * @param layer          the audio layer version
  * @param sampleRate     the sample rate (in samples per second)
  * @param minimumBitRate the minimum bit rate (in bps)
  * @param maximumBitRate the maximum bit rate (in bps)
  * @param duration       the duration (rounded, in milliseconds)
  */
case class Mp3Metadata(version: Int,
                       layer: Int,
                       sampleRate: Int,
                       minimumBitRate: Int,
                       maximumBitRate: Int,
                       duration: Int)
