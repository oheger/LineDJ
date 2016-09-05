/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.client.app

import java.util.concurrent.atomic.AtomicInteger

import net.sf.jguiraffe.gui.app.Application
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''ApplicationSyncStartup''.
  */
class ApplicationSyncStartupSpec extends FlatSpec with Matchers {
  "An ApplicationSyncStartup" should "start an application directly" in {
    val ConfigName = "ApplicationTestConfig"
    val countArgs = new AtomicInteger
    val countRun = new AtomicInteger
    val testApp = new Application {
      override def processCommandLine(args: Array[String]): Unit = {
        args should have length 0
        getConfigResourceName should be(ConfigName)
        countArgs.incrementAndGet()
      }

      override def run(): Unit = {
        countRun.incrementAndGet()
      }
    }
    val startup = new ApplicationSyncStartup {}

    startup.startApplication(testApp, ConfigName)
    countArgs.get() should be(1)
    countRun.get() should be(1)
  }
}
