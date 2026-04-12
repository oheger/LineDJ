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

package de.oliver_heger.linedj.archive.server.cloud

import de.oliver_heger.linedj.server.common.ServerRunner
import org.mockito.ArgumentMatchers.{any, eq as eqArg}
import org.mockito.Mockito.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for [[Server]].
  */
class ServerSpec extends AnyFlatSpec with Matchers with MockitoSugar:
  "Server" should "run a server instance" in :
    val runner = mock[ServerRunner]
    val handle = mock[ServerRunner.ServerHandle]
    doReturn(handle).when(runner).launch(any(), any())

    val server = new Server(runner)
    server.run()

    verify(runner).launch(eqArg("archiveServerCloud"), any())
    verify(handle).awaitShutdown()
