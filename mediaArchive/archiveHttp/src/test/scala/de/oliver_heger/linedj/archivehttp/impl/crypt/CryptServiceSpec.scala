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

package de.oliver_heger.linedj.archivehttp.impl.crypt

import java.security.SecureRandom
import java.util.Base64

import akka.actor.ActorSystem
import akka.stream.scaladsl.{FileIO, Sink, Source}
import akka.testkit.TestKit
import akka.util.ByteString
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.archivehttp.crypt.AESKeyGenerator
import de.oliver_heger.linedj.archivehttp.impl.crypt.CryptServiceSpec.Key
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration._

object CryptServiceSpec {
  /** The password for crypt operations. */
  private val Password = "secret_password!"

  /** Key specification for the test password. */
  private val Key = new AESKeyGenerator().generateKey(Password)
}

/**
  * Test class for ''CryptService''.
  */
class CryptServiceSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike with BeforeAndAfterAll
  with Matchers with FileTestHelper {
  def this() = this(ActorSystem("CryptServiceSpec"))

  override protected def afterAll(): Unit = {
    super.afterAll()
    TestKit shutdownActorSystem system
  }

  /**
    * Reads all the data contained in the given source.
    *
    * @param source the source
    * @return the content of this source
    */
  private def readSource(source: Source[ByteString, Any]): ByteString = {
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    val futResult = source.runWith(sink)
    Await.result(futResult, 3.seconds)
  }

  /**
    * Applies the decrypt function from the test service to the given source
    * and reads its content.
    *
    * @param source the source
    * @return the decrypted content of this source
    */
  private def decryptAndReadSource(source: Source[ByteString, Any]): ByteString = {
    implicit val random: SecureRandom = new SecureRandom()
    readSource(CryptService.decryptSource(Key, source))
  }

  import CryptServiceSpec._

  "CryptService" should "decrypt a file name" in {
    implicit val random: SecureRandom = new SecureRandom()
    val EncName = "Ah56qLiZLMv9NdTkF_Z0WXo32dDcu4g="
    val PlainName = "folder1"

    CryptService.decryptName(Key, EncName) should be(PlainName)
  }

  it should "throw an exception if a name to be decrypted does not start with an IV" in {
    implicit val random: SecureRandom = new SecureRandom()

    intercept[IllegalArgumentException] {
      CryptService.decryptName(Key, "Ah56qLiZ")
    }
  }

  it should "decrypt a source" in {
    val path = resolveResourceFile("encrypted.dat")
    val source = FileIO.fromPath(path)

    val decrypted = decryptAndReadSource(source).utf8String
    decrypted should be(FileTestHelper.TestData)
  }

  it should "handle an empty source to decrypt" in {
    val decrypted = decryptAndReadSource(Source.empty)

    decrypted should be(ByteString.empty)
  }

  it should "handle a source with a single chunk to decrypt" in {
    val EncText = Base64.getUrlDecoder.decode("Ah56qLiZLMv9NdTkF_Z0WXo32dDcu4g=")
    val PlainText = "folder1"
    val source = Source.single(ByteString(EncText))

    val decrypted = decryptAndReadSource(source).utf8String
    decrypted should be(PlainText)
  }
}
