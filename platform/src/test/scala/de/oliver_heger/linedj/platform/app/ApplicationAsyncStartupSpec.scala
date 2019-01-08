/*
 * Copyright 2015-2019 The Developers Team.
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

package de.oliver_heger.linedj.platform.app

import java.util.concurrent.{TimeUnit, CountDownLatch}

import net.sf.jguiraffe.gui.app.Application
import org.scalatest.{Matchers, FlatSpec}

/**
  * Test class for ''ApplicationAsyncStartup'' and the base trait.
  */
class ApplicationAsyncStartupSpec extends FlatSpec with Matchers {
  /**
    * Waits until the given latch is triggered. Fails if this does not
    * happen within a timeout.
    * @param l the latch
    */
  private def await(l: CountDownLatch): Unit = {
    l.await(10, TimeUnit.SECONDS) shouldBe true
  }

  "An ApplicationAsyncStartup" should "start an application in a background thread" in {
    val AppName = "ApplicationTest"
    val latchArgs = new CountDownLatch(1)
    val latchRun = new CountDownLatch(1)
    val testApp = new Application {
      override def processCommandLine(args: Array[String]): Unit = {
        args should have length 0
        getConfigResourceName should be(AppName + "_config.xml")
        latchArgs.countDown()
      }

      override def run(): Unit = {
        latchRun.countDown()
      }
    }

    val startup = new ApplicationAsyncStartup {}
    startup.startApplication(testApp, AppName)
    await(latchArgs)
    await(latchRun)
  }
}
