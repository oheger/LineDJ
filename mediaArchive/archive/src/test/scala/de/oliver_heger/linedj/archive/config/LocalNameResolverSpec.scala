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

package de.oliver_heger.linedj.archive.config

import java.net.InetAddress

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{Failure, Success, Try}

/**
  * Test class for ''LocalNameResolver''.
  */
class LocalNameResolverSpec extends AnyFlatSpec with Matchers {
  "A LocalNameResolver" should "resolve the local host name" in {
    val localHost = Try(InetAddress.getLocalHost)
    localHost match {
      case Failure(_) =>
        println("Cannot resolve local host; skipping test.")
      case Success(address) =>
        LocalNameResolver.localHostName should be(address.getHostName)
    }
  }
}
