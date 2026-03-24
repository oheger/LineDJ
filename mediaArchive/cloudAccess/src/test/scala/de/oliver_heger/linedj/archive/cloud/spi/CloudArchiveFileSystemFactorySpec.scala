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

package de.oliver_heger.linedj.archive.cloud.spi

import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.Locale

/**
  * Test class for [[CloudArchiveFileSystemFactory]].
  */
class CloudArchiveFileSystemFactorySpec extends AnyFlatSpec, Matchers:
  "CloudArchiveFileSystemFactory" should "check the existence of an unknown factory" in :
    CloudArchiveFileSystemFactory.existsFactory("unknown-factory") shouldBe false

  it should "check the existence of a known factory" in :
    CloudArchiveFileSystemFactory.existsFactory(CloudArchiveFileSystemFactoryForTesting.FactoryName) shouldBe true

  it should "check the existence of a known factory ignoring case" in :
    val names = List(
      CloudArchiveFileSystemFactoryForTesting.FactoryName.toLowerCase(Locale.ROOT),
      CloudArchiveFileSystemFactoryForTesting.FactoryName.toUpperCase(Locale.ROOT)
    )

    forEvery(names): name =>
      CloudArchiveFileSystemFactory.existsFactory(name) shouldBe true

  it should "return an existing factory" in :
    val factory = CloudArchiveFileSystemFactory.getFactory(CloudArchiveFileSystemFactoryForTesting.FactoryName)

    factory shouldBe a[CloudArchiveFileSystemFactoryForTesting]

  it should "return an existing factory ignoring case" in :
    val names = List(
      CloudArchiveFileSystemFactoryForTesting.FactoryName.toLowerCase(Locale.ROOT),
      CloudArchiveFileSystemFactoryForTesting.FactoryName.toUpperCase(Locale.ROOT)
    )

    forEvery(names): name =>
      val factory = CloudArchiveFileSystemFactory.getFactory(name)
      factory shouldBe a[CloudArchiveFileSystemFactoryForTesting]

  it should "throw an exception when requesting a non-existing factory" in :
    val NonExistingFactoryName = "nonExistingFactory"
    val exception = intercept[NoSuchElementException]:
      CloudArchiveFileSystemFactory.getFactory(NonExistingFactoryName)

    exception.getMessage should include(NonExistingFactoryName)
