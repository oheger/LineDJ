/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.player.engine

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{verifyNoInteractions, when}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.{ExecutionContext, Future}

/**
  * Test class for [[CompositeAsyncAudioStreamFactory]].
  */
class CompositeAsyncAudioStreamFactorySpec extends AsyncFlatSpec with Matchers with MockitoSugar:
  /** The execution context used by test instances. */
  private val testEc: ExecutionContext = ExecutionContext.global

  private def createFactory(factories: AsyncAudioStreamFactory*): CompositeAsyncAudioStreamFactory =
    new CompositeAsyncAudioStreamFactory(factories)(using testEc)

  "CompositeAsyncAudioStreamFactory" should "return a failed future if no child factory supports the URI" in :
    val AudioFileUri = "unsupportedAudio.uri"
    val childFactories: List[AsyncAudioStreamFactory] = List(mock, mock, mock)
    childFactories.foreach: factory =>
      when(factory.playbackDataForAsync(AudioFileUri))
        .thenReturn(Future.failed(new AsyncAudioStreamFactory.UnsupportedUriException(AudioFileUri)))

    recoverToExceptionIf[AsyncAudioStreamFactory.UnsupportedUriException]:
      createFactory(childFactories *).playbackDataForAsync(AudioFileUri)
    .map: exception =>
      exception.uri should be(AudioFileUri)

  it should "return the result from the first supporting child factory" in :
    val AudioFileUri = "supportedAudio.uri"
    val childFactory1 = mock[AsyncAudioStreamFactory]
    val childFactory2 = mock[AsyncAudioStreamFactory]
    val childFactory3 = mock[AsyncAudioStreamFactory]
    val creator = mock[AudioStreamFactory.AudioStreamCreator]
    val playbackData = AudioStreamFactory.AudioStreamPlaybackData(
      streamCreator = creator,
      streamFactoryLimit = 17384
    )
    when(childFactory1.playbackDataForAsync(AudioFileUri))
      .thenReturn(Future.failed(new AsyncAudioStreamFactory.UnsupportedUriException(AudioFileUri)))
    when(childFactory2.playbackDataForAsync(AudioFileUri)).thenReturn(Future.successful(playbackData))

    createFactory(childFactory1, childFactory2, childFactory3).playbackDataForAsync(AudioFileUri).map: data =>
      verifyNoInteractions(childFactory3)
      data should be(playbackData)

  it should "propagate an arbitrary failure from a child factory" in :
    val exception = new IllegalStateException("Test exception: Cannot create audio stream.")
    val childFactory1 = mock[AsyncAudioStreamFactory]
    val childFactory2 = mock[AsyncAudioStreamFactory]
    when(childFactory1.playbackDataForAsync(any())).thenReturn(Future.failed(exception))

    recoverToExceptionIf[IllegalStateException]:
      createFactory(childFactory1, childFactory2).playbackDataForAsync("someUri")
    .map: producedException =>
      producedException should be(exception)
      verifyNoInteractions(childFactory2)
      succeed
