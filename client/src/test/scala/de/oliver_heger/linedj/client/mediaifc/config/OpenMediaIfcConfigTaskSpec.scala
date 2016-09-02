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

package de.oliver_heger.linedj.client.mediaifc.config

import de.oliver_heger.linedj.client.app.{ClientApplication, ClientApplicationContext,
ClientManagementApplication}
import net.sf.jguiraffe.gui.app.Application
import net.sf.jguiraffe.gui.builder.components.WidgetHandler
import net.sf.jguiraffe.locators.Locator
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''OpenMediaIfcConfigTask''.
  */
class OpenMediaIfcConfigTaskSpec extends FlatSpec with Matchers with MockitoSugar {
  "An OpenMediaIfcConfigTask" should "handle an init message if no dialog is supported" in {
    val helper = new OpenMediaIfcConfigTaskTestHelper
    val task = helper.createTask()

    task receive helper.createInitializedMessage(None)
    helper.expectWidgetState(visible = false)
    task.getCommand should be(null)
  }

  it should "handle an init message if there is a configuration dialog" in {
    val helper = new OpenMediaIfcConfigTaskTestHelper
    val configData = helper.createConfigData()
    val task = helper.createTask()

    task receive helper.createInitializedMessage(Some(configData))
    helper.expectWidgetState(visible = true).checkCommand(task, configData)
  }

  it should "handle a config updated message" in {
    val helper = new OpenMediaIfcConfigTaskTestHelper
    val configData = helper.createConfigData()
    val task = helper.createTask()

    task receive ClientManagementApplication.MediaIfcConfigUpdated(Some(configData))
    helper.expectWidgetState(visible = true).checkCommand(task, configData)
  }

  /**
    * A test helper class which manages dependencies of an instance under test.
    */
  private class OpenMediaIfcConfigTaskTestHelper {
    /** The application associated with the task. */
    val app = mock[ClientApplication]

    /** The widget managed by the task. */
    val widget = mock[WidgetHandler]

    /**
      * Creates a test task instance.
      *
      * @return the task
      */
    def createTask(): OpenMediaIfcConfigTask =
    new OpenMediaIfcConfigTask(widget) {
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
    def createInitializedMessage(config: Option[MediaIfcConfigData]): ClientApplication
    .ClientApplicationInitialized = {
      val appCtx = mock[ClientApplicationContext]
      when(app.clientApplicationContext).thenReturn(appCtx)
      when(appCtx.mediaIfcConfig).thenReturn(config)
      ClientApplication.ClientApplicationInitialized(app)
    }

    /**
      * Checks whether the associated widget's visible state has been set
      * correctly.
      *
      * @param visible the expected visible state
      * @return this helper
      */
    def expectWidgetState(visible: Boolean): OpenMediaIfcConfigTaskTestHelper = {
      verify(widget).setVisible(visible)
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
