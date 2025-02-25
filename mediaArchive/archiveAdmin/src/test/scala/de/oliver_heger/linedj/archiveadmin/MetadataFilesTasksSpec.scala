/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.archiveadmin

import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for the action tasks related to metadata files.
  */
class MetadataFilesTasksSpec extends AnyFlatSpec with Matchers with MockitoSugar:
  "A RefreshFilesTask" should "correctly invoke the controller" in:
    val controller = mock[MetadataFilesController]
    val task = new RefreshFilesTask(controller)

    task.run()
    verify(controller).refresh()

  "A RemoveFilesTask" should "correctly invoke the controller" in:
    val controller = mock[MetadataFilesController]
    val task = new RemoveFilesTask(controller)

    task.run()
    verify(controller).removeFiles()

  "A CloseMetadataFilesDialogTask" should "correctly invoke the controller" in:
    val controller = mock[MetadataFilesController]
    val task = new CloseMetadataFilesDialogTask(controller)

    task.run()
    verify(controller).close()
