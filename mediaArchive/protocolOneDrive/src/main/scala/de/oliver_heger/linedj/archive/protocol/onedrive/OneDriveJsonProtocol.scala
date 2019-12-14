/*
 * Copyright 2015-2019 The Developers Team.
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

package de.oliver_heger.linedj.archive.protocol.onedrive

import spray.json.{DefaultJsonProtocol, RootJsonFormat}

/**
  * A data class representing an item in a OneDrive JSON response for a folder
  * request. Here we are only interested in a minimum set of properties.
  *
  * @param name the name of the item
  */
case class OneDriveItem(name: String)

/**
  * A data class representing the OneDrive JSON response for a folder request.
  * Here we are only interested in the list with the child items of the
  * current folder. For large folders, the content is distributed over multiple
  * pages. In this case, a link for the next page is available.
  *
  * @param value    a list with the child items of this folder
  * @param nextLink an optional link to the next page
  */
case class OneDriveModel(value: List[OneDriveItem], nextLink: Option[String])

/**
  * An object defining converters for the data classes to be extracted from
  * OneDrive JSON responses.
  *
  * The implicits defined by this object must be available in the current scope
  * in order to deserialize JSON responses to the corresponding object model.
  */
object OneDriveJsonProtocol extends DefaultJsonProtocol {
  implicit val itemFormat: RootJsonFormat[OneDriveItem] = jsonFormat1(OneDriveItem)
  implicit val modelFormat: RootJsonFormat[OneDriveModel] =
    jsonFormat(OneDriveModel.apply, "value", "@odata.nextLink")
}
