/*
 * Copyright 2015-2017 The Developers Team.
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

import akka.util.ByteString

/**
  * A message containing data extracted from an ID3 frame to be processed.
  *
  * Messages of this type are produced for the binary content of ID3v2 frames
  * found in an MP3 audio file. Depending on the size of the frame, multiple
  * messages of this type will be generated. The ''lastChunk'' parameter
  * indicates whether a complete frame has been processed.
  *
  * @param frameHeader the header of the affected ID3v2 frame
  * @param data        binary data of the frame
  * @param lastChunk   a flag whether this is the last chunk of this ID3 frame
  */
case class ProcessID3FrameData(frameHeader: ID3Header, data: ByteString,
                               lastChunk: Boolean)

/**
  * A message indicating that an ID3 frame is incomplete.
  *
  * Messages of this type are generated when an audio stream ends at an
  * unexpected position while ID3 data is still processed. In this case, the
  * processor actor has to be notified because it would otherwise wait for
  * the last chunk of data to arrive.
  *
  * @param frameHeader the header of the affected ID3v2 frame
  */
case class IncompleteID3Frame(frameHeader: ID3Header)
