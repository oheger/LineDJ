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

package de.oliver_heger.linedj.archivehttp.impl.io

import akka.http.scaladsl.model.HttpResponse
import de.oliver_heger.linedj.archivehttp.http.HttpRequests

/**
  * A special exception class indicating a failed HTTP request.
  *
  * A request sent to the [[HttpRequestActor]] can fail for different reasons.
  * This exception class collects the information available. The fields that
  * are actually defined depend on the type of the failure; for instance, the
  * HTTP response is available only if a response from the server has been
  * received.
  *
  * @param message  an error message
  * @param cause    the cause of this exception
  * @param response optional server response indicating the failed request
  * @param request  the original failed request
  */
case class FailedRequestException(message: String,
                                  cause: Throwable,
                                  response: Option[HttpResponse],
                                  request: HttpRequests.SendRequest)
  extends Exception(message, cause)
