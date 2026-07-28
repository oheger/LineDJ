/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.apps.archive.login

import net.sf.jguiraffe.gui.app.ApplicationBuilderData
import net.sf.jguiraffe.locators.Locator
import org.mockito.Mockito.verify
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.atomic.AtomicReference

/**
  * Test class for [[OpenCredentialDialogCommand]].
  */
class OpenCredentialDialogCommandSpec extends AnyFlatSpec, Matchers, MockitoSugar:
  "An OpenCredentialDialogCommand" should "pass the locator to the super class" in :
    val locator = mock[Locator]

    val command = new OpenCredentialDialogCommand(locator, new AtomicReference)

    command.getLocator should be(locator)

  it should "store the current credential in the builder data" in :
    val Credential = "MyArchiveCredential"
    val builderData = mock[ApplicationBuilderData]
    val archiveRef = new AtomicReference(Credential)
    val command = new OpenCredentialDialogCommand(mock[Locator], archiveRef)

    command.prepareBuilderData(builderData)

    verify(builderData).addProperty(OpenCredentialDialogCommand.PropertyCredential, Credential)
