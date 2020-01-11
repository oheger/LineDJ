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

package de.oliver_heger.linedj.archivehttpstart

import java.util.concurrent.atomic.AtomicReference

import net.sf.jguiraffe.gui.app.ApplicationBuilderData
import net.sf.jguiraffe.locators.Locator
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for the commands that open dialogs related to archives.
  */
class OpenDlgCommandsSpec extends FlatSpec with Matchers with MockitoSugar {
  "An OpenLoginDlgCommand" should "pass the locator to the super class" in {
    val locator = mock[Locator]
    val command = new OpenLoginDlgCommand(locator, new AtomicReference[ArchiveRealm])

    command.getLocator should be(locator)
  }

  it should "store the current realm in the builder data" in {
    val Realm = mock[ArchiveRealm]
    val builderData = mock[ApplicationBuilderData]
    val realmRef = new AtomicReference(Realm)
    val command = new OpenLoginDlgCommand(mock[Locator], realmRef)

    command.prepareBuilderData(builderData)
    verify(builderData).addProperty(OpenDlgCommand.PropertyRealm, Realm)
  }

  "An OpenUnlockDlgCommand" should "pass the locator to the super class" in {
    val locator = mock[Locator]
    val command = new OpenUnlockDlgCommand(locator, new AtomicReference[String])

    command.getLocator should be(locator)
  }

  it should "store the current archive name in the builder data" in {
    val Archive = "MyTestArchive"
    val builderData = mock[ApplicationBuilderData]
    val archiveRef = new AtomicReference(Archive)
    val command = new OpenUnlockDlgCommand(mock[Locator], archiveRef)

    command.prepareBuilderData(builderData)
    verify(builderData).addProperty(OpenDlgCommand.PropertyArchiveName, Archive)
  }
}
