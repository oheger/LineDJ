/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.radio

import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''StartPlaybackTask''.
  */
class StartPlaybackTaskSpec extends AnyFlatSpec with MockitoSugar {
  "A StartPlaybackTask" should "call the controller's startPlayback() method" in {
    val controller = mock[RadioController]
    val task = new StartPlaybackTask(controller)

    task.run()
    verify(controller).startPlayback()
  }
}
