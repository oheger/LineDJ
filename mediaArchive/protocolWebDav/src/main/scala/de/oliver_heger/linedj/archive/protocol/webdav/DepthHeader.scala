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

package de.oliver_heger.linedj.archive.protocol.webdav

import akka.http.scaladsl.model.headers.{ModeledCustomHeader, ModeledCustomHeaderCompanion}

import scala.util.Try

/**
  * Class representing the ''Depth'' header.
  *
  * This header has to be included to WebDav requests. It defines the depth of
  * sub structures to be returned by a ''PROPFIND'' request.
  *
  * @param depth the value of the header
  */
class DepthHeader(depth: String) extends ModeledCustomHeader[DepthHeader] {
  override val companion: ModeledCustomHeaderCompanion[DepthHeader] = DepthHeader

  override def value(): String = depth

  override def renderInRequests(): Boolean = true

  override def renderInResponses(): Boolean = true
}

object DepthHeader extends ModeledCustomHeaderCompanion[DepthHeader] {
  /** Constant for the default Depth header used in folder requests. */
  val DefaultDepthHeader: DepthHeader = DepthHeader("1")

  override val name: String = "Depth"

  override def parse(value: String): Try[DepthHeader] =
    Try(new DepthHeader(value))
}
