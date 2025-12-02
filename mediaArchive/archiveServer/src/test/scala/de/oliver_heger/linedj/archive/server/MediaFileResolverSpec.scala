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

package de.oliver_heger.linedj.archive.server

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.scalatest.OptionValues
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.IOException
import scala.concurrent.Future

/**
  * Test class for [[MediaFileResolver]].
  */
class MediaFileResolverSpec extends AsyncFlatSpec with Matchers with MockitoSugar with OptionValues:
  "toOptionalSource" should "map a successful future to a defined Option" in :
    val source = mock[Source[ByteString, NotUsed]]
    val futSource = Future.successful(source)

    MediaFileResolver.toOptionalSource(futSource).map: optSource =>
      optSource.value should be(source)

  it should "handle an unspecific exception" in :
    val exception = new IOException("Test exception: Error when resolving file.")
    val futSource: Future[Source[ByteString, NotUsed]] = Future.failed(exception)

    recoverToExceptionIf[IOException]:
      MediaFileResolver.toOptionalSource(futSource)
    .map: mappedException =>
      mappedException should be(exception)

  it should "handle an exception for an unresolvable file" in :
    val exception = new MediaFileResolver.UnresolvableFileException("testFileID", "A test exception")
    val futSource: Future[Source[ByteString, NotUsed]] = Future.failed(exception)

    MediaFileResolver.toOptionalSource(futSource).map: optSource =>
      optSource shouldBe empty

  "UnresolvableFileException" should "generate a standard message" in :
    val fileID = "non-resolvable-media-file"
    val exception = new MediaFileResolver.UnresolvableFileException(fileID)

    exception.fileID should be(fileID)
    exception.getMessage should include(fileID)
