/*
 * Copyright 2015-2022 The Developers Team.
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

package de.oliver_heger.linedj.platform.mediaifc.config

import de.oliver_heger.linedj.platform.app.{ApplicationManager, ClientApplication, ClientApplicationContext, ClientManagementApplication}
import net.sf.jguiraffe.gui.app.Application
import net.sf.jguiraffe.locators.Locator
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''OpenMediaIfcConfigTask''.
  */
class OpenMediaIfcConfigTaskSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  "An OpenMediaIfcConfigTask" should "handle an init message if no dialog is supported" in {
    val helper = new OpenMediaIfcConfigTaskTestHelper
    val task = helper.createTask()

    task receive helper.createInitializedMessage(None)
    helper.expectStateUpdate(visible = false)
    task.getCommand should be(null)
  }

  it should "handle an init message if there is a configuration dialog" in {
    val helper = new OpenMediaIfcConfigTaskTestHelper
    val configData = helper.createConfigData()
    val task = helper.createTask()

    task receive helper.createInitializedMessage(Some(configData))
    helper.expectStateUpdate(visible = true).checkCommand(task, configData)
  }

  it should "handle a config updated message" in {
    val helper = new OpenMediaIfcConfigTaskTestHelper
    val configData = helper.createConfigData()
    val task = helper.createTask()

    task receive ClientManagementApplication.MediaIfcConfigUpdated(Some(configData))
    helper.expectStateUpdate(visible = true).checkCommand(task, configData)
  }

  it should "create a dummy state handler" in {
    val task = new OpenMediaIfcConfigTask

    task.stateHandler.updateState(configAvailable = true)
  }

  /**
    * A test helper class which manages dependencies of an instance under test.
    */
  private class OpenMediaIfcConfigTaskTestHelper {
    /** The application associated with the task. */
    val app: ClientApplication = mock[ClientApplication]

    /** The widget managed by the task. */
    val stateHandler: MediaIfcConfigStateHandler = mock[MediaIfcConfigStateHandler]

    /**
      * Creates a test task instance.
      *
      * @return the task
      */
    def createTask(): OpenMediaIfcConfigTask =
    new OpenMediaIfcConfigTask(stateHandler) {
      override def getApplication: Application = app
    }

    /**
      * Creates an initialized mock with configuration data.
      *
      * @return the mock config data
      */
    def createConfigData(): MediaIfcConfigData = {
      val data = mock[MediaIfcConfigData]
      when(data.configScriptLocator).thenReturn(mock[Locator])
      data
    }

    /**
      * Creates an initialized message with a mock application whose context
      * returns the specified config data for the media interface.
      *
      * @param config the optional config for the media interface
      * @return the initialized message
      */
    def createInitializedMessage(config: Option[MediaIfcConfigData]): ApplicationManager
    .ApplicationRegistered = {
      val appCtx = mock[ClientApplicationContext]
      when(app.clientApplicationContext).thenReturn(appCtx)
      when(appCtx.mediaIfcConfig).thenReturn(config)
      ApplicationManager.ApplicationRegistered(app)
    }

    /**
      * Checks whether the associated state handler's state has been set
      * correctly.
      *
      * @param visible the expected state
      * @return this helper
      */
    def expectStateUpdate(visible: Boolean): OpenMediaIfcConfigTaskTestHelper = {
      verify(stateHandler).updateState(visible)
      this
    }

    /**
      * Checks whether the task's command has been initialized correctly.
      *
      * @param task       the task
      * @param configData the expected config data object
      * @return this helper
      */
    def checkCommand(task: OpenMediaIfcConfigTask, configData: MediaIfcConfigData):
    OpenMediaIfcConfigTaskTestHelper = {
      val cmd = task.getCommand.asInstanceOf[OpenMediaIfcConfigCommand]
      cmd.configData should be(configData)
      cmd.getApplication should be(app)
      this
    }
  }

}
