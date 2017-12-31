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

package de.oliver_heger.linedj.player.ui

import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.audio._
import de.oliver_heger.linedj.player.engine.PlaybackProgressEvent
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.reflect.ClassTag

/**
  * Test class for the different action tasks of the audio player UI
  * application.
  */
class ActionTasksSpec extends FlatSpec with Matchers with MockitoSugar {
  /**
    * Executes a test for an action task that produces a result on the UI
    * message bus. This method creates a pre-configured mock for an UI
    * controller and a task to be tested using the provided creator function.
    * The task is then executed, and the message published by it on the
    * message bus is captured.
    *
    * @param taskCreator the function to create the task
    * @param state       the last player state reported by the controller
    * @param progress    the last progress event reported by the controller
    * @param t           class tag for the expected message on the bus
    * @tparam T the type of the expected message on the bus
    * @return the result object published on the bus
    */
  private def checkTaskWithBusResult[T](state: AudioPlayerState = mock[AudioPlayerState],
                                        progress: PlaybackProgressEvent
                                        = mock[PlaybackProgressEvent])
                                       (taskCreator: UIController => PlayerActionTask)
                                       (implicit t: ClassTag[T]): T = {
    val bus = new MessageBusTestImpl
    val controller = mock[UIController]
    when(controller.messageBus).thenReturn(bus)
    when(controller.lastPlayerState).thenReturn(state)
    when(controller.lastProgressEvent).thenReturn(progress)

    val task = taskCreator(controller)
    task.run()

    verify(controller).playerActionTriggered()
    bus.expectMessageType[T]
  }

  "A StartPlaybackTask" should "send a correct command on the message bus" in {
    val cmd = checkTaskWithBusResult[StartAudioPlayback]()(new StartPlaybackTask(_))
    cmd should be(StartAudioPlayback())
  }

  "A StopPlaybackTask" should "send a correct command on the message bus" in {
    val cmd = checkTaskWithBusResult[StopAudioPlayback]()(new StopPlaybackTask(_))
    cmd should be(StopAudioPlayback())
  }

  "A NextSongTask" should "send a correct command on the message bus" in {
    val cmd = checkTaskWithBusResult[AudioPlayerCommand]()(new NextSongTask(_))
    cmd should be(SkipCurrentSource)
  }
}
