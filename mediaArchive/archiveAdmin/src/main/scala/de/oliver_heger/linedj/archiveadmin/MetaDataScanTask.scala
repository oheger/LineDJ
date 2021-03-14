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
import net.sf.jguiraffe.gui.builder.action.{ActionTask, FormAction}
import net.sf.jguiraffe.gui.builder.event.BuilderEvent

/**
  * A class implementing base functionality for action task classes that affect
  * meta data scan operations.
  *
  * This class is going to be extended by implementations for the commands to
  * start or cancel a media scan. In this case, specific commands have to be
  * sent to the media archive. The concrete commands have to be provided by
  * concrete subclasses.
  *
  * @param facade  the media facade
  * @param message the message to be sent to the media archive
  */
class MetaDataScanTask(val facade: MediaFacade, val message: Any)
  extends ActionTask {
  /**
    * @inheritdoc This implementation sends the message to the meta data
    *             actor and then disables the action (to prevent multiple
    *             triggers).
    */
  override def run(action: FormAction, event: BuilderEvent): Unit = {
    facade.send(MediaActors.MediaManager, message)
    action setEnabled false
  }
}

/**
  * An action task class that starts a new meta data scan.
  *
  * @param facade the media facade
  */
class StartMetaDataScanTask(facade: MediaFacade)
  extends MetaDataScanTask(facade, ScanAllMedia)

/**
  * An action task class that cancels a currently running meta data scan.
  *
  * @param facade the media facade
  */
class CancelMetaDataScanTask(facade: MediaFacade)
  extends MetaDataScanTask(facade, CloseRequest)
