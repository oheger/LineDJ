/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.archiveadmin

import java.util.concurrent.atomic.AtomicReference

import net.sf.jguiraffe.gui.app.ApplicationBuilderData
import net.sf.jguiraffe.locators.Locator
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for helper class related to UI.
  */
class UIHelpersSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  "EmptyArchiveComponentsListModel" should "return the correct component type" in {
    val model = new EmptyArchiveComponentsListModel

    model.getType should be(classOf[String])
  }

  "ArchiveComponentsListChangeHandler" should "handle a change event" in {
    val controller = mock[ArchiveAdminController]
    val handler = new ArchiveComponentsListChangeHandler(controller)

    handler.elementChanged(null)
    verify(controller).archiveSelectionChanged()
  }

  "OpenMetaDataFilesDlgCommand" should "pass the locator to the base class" in {
    val locator = mock[Locator]
    val command = new OpenMetaDataFilesDlgCommand(locator, new AtomicReference[String])

    command.getLocator should be(locator)
  }

  it should "pass the property for the selected archive to the builder data object" in {
    val ArchiveName = "The selected archive"
    val builderData = mock[ApplicationBuilderData]
    val command = new OpenMetaDataFilesDlgCommand(mock[Locator], new AtomicReference[String](ArchiveName))

    command.prepareBuilderData(builderData)
    verify(builderData).addProperty(MetaDataFilesController.PropSelectedArchiveID, ArchiveName)
  }
}
