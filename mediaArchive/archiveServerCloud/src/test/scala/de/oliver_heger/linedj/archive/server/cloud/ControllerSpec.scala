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

import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.apache.commons.configuration2.builder.fluent.Configurations
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future

/**
  * Test class for [[Controller]].
  */
class ControllerSpec extends AsyncFlatSpecLike, Matchers, MockitoSugar:
  "The config loader" should "correctly parse the configuration" in :
    val testConfigUrl = getClass.getResource("/cloud-archive-server-config.xml")
    val configs = new Configurations
    val serverConfig = configs.xml(testConfigUrl)

    val controller = new Controller with SystemPropertyAccess {}
    Future.fromTry(controller.configLoader(serverConfig)) map : archiveConfig =>
      archiveConfig.cacheDirectory.toString should be("/tmp/cloud-archives/cache")
      archiveConfig.archives should have size 4
