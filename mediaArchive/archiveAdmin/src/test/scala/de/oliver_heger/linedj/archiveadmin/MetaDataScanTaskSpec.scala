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

import de.oliver_heger.linedj.io.CloseRequest
import de.oliver_heger.linedj.platform.mediaifc.{MediaActors, MediaFacade}
import de.oliver_heger.linedj.shared.archive.media.ScanAllMedia
import net.sf.jguiraffe.gui.builder.action.FormAction
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''MetaDataScanTask''.
  */
class MetaDataScanTaskSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  "A MetaDataScanTask" should "correctly implement the action task" in {
    val facade = mock[MediaFacade]
    val action = mock[FormAction]
    val Message = new Object
    val task = new MetaDataScanTask(facade, Message)

    task.run(action, null)
    verify(facade).send(MediaActors.MediaManager, Message)
    verify(action).setEnabled(false)
  }

  "A StartMetaDataScanTask" should "initialize itself correctly" in {
    val facade = mock[MediaFacade]
    val task = new StartMetaDataScanTask(facade)

    task.facade should be(facade)
    task.message should be(ScanAllMedia)
  }

  "A CancelMetaDataScanTask" should "initialize itself correctly" in {
    val facade = mock[MediaFacade]
    val task = new CancelMetaDataScanTask(facade)

    task.facade should be(facade)
    task.message should be(CloseRequest)
  }
}
