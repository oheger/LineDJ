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
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

object ApplicationSyncStartupSpec {
  /** Constant for an application name. */
  private val AppName = "ApplicationStartupTestApp"

  /** The name of the property with the user configuration. */
  private val PropAppUsrConfig = AppName + "_user_config"
}

/**
  * Test class for ''ApplicationSyncStartup''.
  */
class ApplicationSyncStartupSpec extends FlatSpec with Matchers with BeforeAndAfterAll {
  import ApplicationSyncStartupSpec._

  override protected def afterAll(): Unit = {
    System clearProperty PropAppUsrConfig
  }

  /**
    * Creates a test application.
    *
    * @param usrConfigName the expected user config name
    * @param countArgs  optional counter for command line processing
    * @param countRun   optional counter for run invocations
    * @return the test app
    */
  private def createTestApp(usrConfigName: String,
                            countArgs: AtomicInteger = new AtomicInteger,
                            countRun: AtomicInteger = new AtomicInteger): Application =
    new Application {
      override def processCommandLine(args: Array[String]): Unit = {
        args should have length 0
        getConfigResourceName should be(AppName + "_config.xml")
        System.getProperty(PropAppUsrConfig) should be(usrConfigName)
        countArgs.incrementAndGet()
      }

      override def run(): Unit = {
        countRun.incrementAndGet()
      }
    }

  /**
    * Starts a test application using a test startup.
    *
    * @param app the application to start
    */
  private def startApp(app: Application): Unit = {
    val startup = new ApplicationSyncStartup {}
    startup.startApplication(app, AppName)
  }

  "An ApplicationSyncStartup" should "start an application directly" in {
    val countArgs = new AtomicInteger
    val countRun = new AtomicInteger

    startApp(createTestApp(".lineDJ-" + AppName + ".xml", countArgs, countRun))
    countArgs.get() should be(1)
    countRun.get() should be(1)
  }

  it should "evaluate the LineDJ_ApplicationID property" in {
    val ConfigPrefix = ".CoolApp"
    System.setProperty("LineDJ_ApplicationID", ConfigPrefix)
    try {
      startApp(createTestApp(".lineDJ-" + ConfigPrefix + "-" + AppName + ".xml"))
    } finally {
      System clearProperty "LineDJ_ApplicationID"
    }
  }

  it should "evaluate a system property for a configuration name" in {
    val ConfigProperty = AppName + "_config"
    val ConfigFile = ".mapped-config.xml"
    System.setProperty(ConfigProperty, ConfigFile)
    try {
      startApp(createTestApp(ConfigFile))
    } finally {
      System clearProperty ConfigProperty
    }
  }
}
