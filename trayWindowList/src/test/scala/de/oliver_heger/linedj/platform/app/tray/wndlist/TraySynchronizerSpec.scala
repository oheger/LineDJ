/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.platform.app.tray.wndlist

import java.util.concurrent.{SynchronousQueue, TimeUnit}
import javax.swing.SwingUtilities

import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''TraySynchronizer''.
  */
class TraySynchronizerSpec extends FlatSpec with Matchers {
  "A TraySynchronizer" should "schedule an action on AWT thread" in {
    val queue = new SynchronousQueue[Boolean]
    val sync = new TraySynchronizer

    sync.schedule {
      queue.put(SwingUtilities.isEventDispatchThread)
    }
    val result = queue.poll(10, TimeUnit.SECONDS)
    result shouldBe true
  }
}
