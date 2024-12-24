/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.actors

import de.oliver_heger.linedj.player.engine.AsyncAudioStreamFactory.UnsupportedUriException
import de.oliver_heger.linedj.player.engine.AudioStreamFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.mockito.Mockito.*
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, BeforeAndAfterAll}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future

/**
  * Test class for [[DynamicAudioStreamFactory]].
  */
class DynamicAudioStreamFactorySpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar:
  def this() = this(ActorSystem("DynamicAudioStreamFactorySpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  /**
    * Executes a test with a newly created [[DynamicAudioStreamFactory]]
    * object. After the test, the object is properly shutdown.
    *
    * @param test the function containing the test logic
    * @return the result of the test execution
    */
  private def runTest(test: DynamicAudioStreamFactory => Future[Assertion]): Future[Assertion] =
    val factory = DynamicAudioStreamFactory()
    try
      test(factory)
    finally
      factory.shutdown()

  "A DynamicAudioStreamFactory" should "return a failed future if there are no child factories" in :
    runTest { factory =>
      val audioUri = "someAudioFile.mp3"
      recoverToExceptionIf[UnsupportedUriException](factory.playbackDataForAsync(audioUri)).map { exception =>
        exception.uri should be(audioUri)
      }
    }

  it should "support adding child factories that are then queried" in :
    val audioUri = "toBeResolved.mp3"
    val creator = mock[AudioStreamFactory.AudioStreamCreator]
    val playbackData = AudioStreamFactory.AudioStreamPlaybackData(streamCreator = creator,
      streamFactoryLimit = 20354)
    val child1 = mock[AudioStreamFactory]
    val child2 = mock[AudioStreamFactory]
    when(child1.playbackDataFor(audioUri)).thenReturn(Some(playbackData))
    when(child2.playbackDataFor(audioUri)).thenReturn(None)

    runTest { factory =>
      factory.addAudioStreamFactory(child1)
      factory.addAudioStreamFactory(child2)

      factory.playbackDataForAsync(audioUri).map { result =>
        verify(child2).playbackDataFor(audioUri)
        result should be(playbackData)
      }
    }

  it should "support removing child factories" in :
    val audioUri = "noLongerSupported.mp3"
    val child1 = mock[AudioStreamFactory]
    val child2 = mock[AudioStreamFactory]
    when(child2.playbackDataFor(audioUri)).thenReturn(None)

    runTest { factory =>
      factory.addAudioStreamFactory(child1)
      factory.addAudioStreamFactory(child2)

      factory.removeAudioStreamFactory(child1)
      recoverToExceptionIf[UnsupportedUriException](factory.playbackDataForAsync(audioUri)).map { exception =>
        verify(child2).playbackDataFor(audioUri)
        verifyNoInteractions(child1)
        exception.uri should be(audioUri)
      }
    }
