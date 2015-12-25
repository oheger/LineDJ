/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.pleditor.playlist.export

import scala.beans.BeanProperty

object ExportSettings {
  /**
   * Constant for the clear mode ''nothing''. In this mode no files are removed
   * from the target medium. New files are just added, if a file with the name
   * of an exported file already exists, the export file is skipped. This is
   * appropriate for doing an incremental export.
   */
  val ClearNothing = 0

  /**
   * Constant for the clear mode ''override''. In this mode it is checked for
   * each file to be exported whether the target medium contains a file with
   * the same name. If so, this file is removed. This basically means that new
   * files override existing ones, but no other files are removed on the
   * target medium.
   */
  val ClearOverride = 1

  /**
   * Constant for the clear mode ''all''. In this mode the whole target medium
   * is cleared before the export files are written.
   */
  val ClearAll = 2
}

/**
 * A simple bean class that defines the parameters required for an export
 * operation.
 *
 * This class is used as bean class by the form for entering the settings for
 * an export.
 */
class ExportSettings {
  /**
   * The directory in which the exported files are written.
   */
  @BeanProperty var targetDirectory: String = _

  @BeanProperty var clearMode = 0
}
