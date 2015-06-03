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

import de.oliver_heger.splaya.mp3.{ID3Header, ID3TagProvider}

/**
 * A message containing data extracted from an ID3 frame to be processed.
 *
 * Messages of this type are produced for each ID3v2 frame found in an MP3
 * audio file. If the frame size is within a configurable limit, its full
 * content is contained. Otherwise, multiple messages of this type are
 * produced for a single ID3 frame.
 *
 * @param path the path to the affected media file
 * @param frameHeader the header of the affected ID3v2 frame
 * @param data binary data of the frame
 * @param lastChunk a flag whether this is the last chunk of this ID3 frame
 */
case class ProcessID3FrameData(path: Path, frameHeader: ID3Header, data: Array[Byte], lastChunk:
Boolean)

/**
 * A message with the meta data result extracted from an ID3v2 frame.
 *
 * A message of this type is generated when an ID3 frame has been fully
 * processed. The actual result - the ID3 tags extracted from the frame - is
 * represented by an [[ID3TagProvider]] object. If the audio file did not
 * contain valid information in this frame, this may be undefined.
 * @param path the path to the affected media file
 * @param frameHeader the header of the processed ID3v2 frame
 * @param metaData an option with the extracted meta data
 */
case class ID3FrameMetaData(path: Path, frameHeader: ID3Header, metaData: Option[ID3TagProvider])
