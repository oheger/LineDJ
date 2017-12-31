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

import de.oliver_heger.linedj.platform.audio.{SkipCurrentSource, StartAudioPlayback,
  StopAudioPlayback}

/**
  * A base action task class that needs to interact with a
  * [[UIController]] instance.
  *
  * This class manages a reference to the controller. It already offers some
  * functionality to handle a default task execution.
  *
  * @param controller the ''UIController''
  */
abstract class PlayerActionTask(val controller: UIController) extends Runnable {
  override def run(): Unit = {
    controller.playerActionTriggered()
    controller.messageBus publish playerCommand
  }

  /**
    * Returns the command to be published on the message bus as an
    * implementation of this task.
    *
    * @return the message bus command
    */
  protected def playerCommand: AnyRef
}

/**
  * Task implementation for starting playback.
  *
  * @param controller the ''UIController''
  */
class StartPlaybackTask(controller: UIController) extends PlayerActionTask(controller) {
  override protected val playerCommand: AnyRef = StartAudioPlayback()
}

/**
  * Task implementation for stopping playback.
  *
  * @param controller the ''UIController''
  */
class StopPlaybackTask(controller: UIController) extends PlayerActionTask(controller) {
  override protected val playerCommand: AnyRef = StopAudioPlayback()
}

/**
  * Task implementation for moving to the next song in the playlist.
  *
  * @param controller the ''UIController''
  */
class NextSongTask(controller: UIController) extends PlayerActionTask(controller) {
  override protected val playerCommand: AnyRef = SkipCurrentSource
}
