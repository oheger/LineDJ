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

package de.oliver_heger.linedj.player.engine.client.config

import org.apache.commons.configuration.HierarchicalConfiguration
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

object ConfigurationExtensionsSpec:
  /** The key for the property with the tet duration. */
  private val DurationKey = "duration"

  private def createConfigWithDuration(value: Int, unit: Option[String] = None): HierarchicalConfiguration =
    val config = new HierarchicalConfiguration
    config.addProperty(DurationKey, value)
    unit foreach { unitValue =>
      config.addProperty(s"$DurationKey[@unit]", unitValue)
    }
    config

/**
  * Test class for the extensions defined on ''Configuration''
  */
class ConfigurationExtensionsSpec extends AnyFlatSpec with Matchers:

  import ConfigurationExtensions._
  import ConfigurationExtensionsSpec._

  "Configuration.getDuration" should "return a duration with the default unit seconds" in:
    val config = createConfigWithDuration(42)

    val duration = config.getDuration(DurationKey)

    duration should be(42.seconds)

  it should "apply the seconds unit" in:
    val config = createConfigWithDuration(42, Some("Seconds"))

    val duration = config.getDuration(DurationKey)

    duration should be(42.seconds)

  it should "apply the minutes unit" in:
    val config = createConfigWithDuration(11, Some("Minutes"))

    val duration = config.getDuration(DurationKey)

    duration should be(11.minutes)

  it should "apply the hours unit" in:
    val config = createConfigWithDuration(2, Some("Hours"))

    val duration = config.getDuration(DurationKey)

    duration should be(2.hours)

  it should "apply the milliseconds unit" in:
    val config = createConfigWithDuration(800, Some("Milliseconds"))

    val duration = config.getDuration(DurationKey)

    duration should be(800.milliseconds)

  it should "support an alias for milliseconds" in:
    val config = createConfigWithDuration(800, Some("Millis"))

    val duration = config.getDuration(DurationKey)

    duration should be(800.milliseconds)

  it should "support case insensitive units in lowercase" in:
    val config = createConfigWithDuration(77, Some("milliseconds"))

    val duration = config.getDuration(DurationKey)

    duration should be(77.milliseconds)

  it should "support case insensitive units in uppercase" in:
    val config = createConfigWithDuration(55, Some("MINUTES"))

    val duration = config.getDuration(DurationKey)

    duration should be(55.minutes)

  it should "fail for an unsupported unit" in:
    val UnsupportedUnit = "LightYears"
    val config = createConfigWithDuration(0, Some(UnsupportedUnit))

    val exception = intercept[IllegalArgumentException]:
      config.getDuration(DurationKey)

    exception.getMessage should include(UnsupportedUnit)

  "Configuration.getDuration with default" should "return the value of a defined key" in:
    val config = createConfigWithDuration(100)

    val duration = config.getDuration(DurationKey, 2.hours)

    duration should be(100.seconds)

  it should "return the default value for an undefined key" in:
    val default = 33.minutes
    val config = new HierarchicalConfiguration

    val duration = config.getDuration(DurationKey, default)

    duration should be(default)
