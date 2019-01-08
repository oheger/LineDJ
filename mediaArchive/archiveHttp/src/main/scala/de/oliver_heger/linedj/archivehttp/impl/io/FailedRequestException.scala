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

package de.oliver_heger.linedj.archivehttp.impl.io

import akka.http.scaladsl.model.HttpResponse

/**
  * A special exception class indicating a failed HTTP request.
  *
  * The exception stores the original response, so that further information
  * about the failure can be obtained.
  *
  * @param response the response indicating the failed request
  */
case class FailedRequestException(response: HttpResponse)
  extends Exception(response.status.value)
