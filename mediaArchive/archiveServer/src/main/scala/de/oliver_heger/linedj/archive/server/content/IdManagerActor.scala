/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.archive.server.content

import de.oliver_heger.linedj.shared.archive.metadata.Checksums

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
  * An actor implementation for generating and managing IDs for a specific
  * type of entities contained in a media archive.
  *
  * The IDs of entities like artists and albums are computed dynamically using
  * hash algorithms on the entity names. Since the computation of such hashes
  * produces some load, the task is delegated to actors, so that it can be
  * performed in parallel. This actor implementation manages a central cache of
  * identifiers. When queried for the ID of a specific entity, it first checks
  * its cache. If the ID has not yet been computed, the computation is
  * triggered now asynchronously.
  */
object IdManagerActor:
  /**
    * Alias of a function that is used to calculate IDs for entities based on
    * their names. The function expects the entity name as input and returns
    * its ID.
    */
  type IdCalculatorFunc = String => String

  /** The algorithm used by the default hash-based ID calculator function. */
  private val HashAlgorithm = "SHA-1"

  /**
    * A default [[IdCalculatorFunc]] that uses a standard hash algorithm to
    * calculate an ID value.
    */
  val HashIdCalculatorFunc: IdCalculatorFunc = input =>
    val digest = MessageDigest.getInstance(HashAlgorithm)
    Checksums.toHexString(digest.digest(input.getBytes(StandardCharsets.ISO_8859_1)))
