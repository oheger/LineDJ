/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.archivehttpstart.app

import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.crypt.alg.aes.Aes
import de.oliver_heger.linedj.{AsyncTestHelper, FileTestHelper}
import de.oliver_heger.linedj.archivehttp.config.UserCredentials
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Path, Paths}
import java.security.Key
import java.util.Base64
import scala.collection.immutable.IndexedSeq
import scala.concurrent.Future

object SuperPasswordStorageServiceSpec:
  /** The password for crypt operations. */
  private val SuperPassword = "SuperDuperPwd!"

  /** Prefix for test realm names. */
  private val PrefixRealm = "realm"

  /** Prefix for test user names. */
  private val PrefixUser = "TestUser"

  /** Prefix for test passwords. */
  private val PrefixPassword = "_Secret_"

  /** Prefix for test archives. */
  private val PrefixArchive = "Archive"

  /** Prefix for passwords used for encryption. */
  private val PrefixCryptPassword = "crypt_"

  /**
    * Generates string-based test data with a prefix and an index.
    *
    * @param prefix the prefix
    * @param index  the index
    * @return the resulting test data
    */
  private def generate(prefix: String, index: Int): String = prefix + index

  /**
    * Generates test user credentials with the given index.
    *
    * @param index the index
    * @return test user credentials with this index
    */
  private def generateCredentials(index: Int): UserCredentials =
    UserCredentials(generate(PrefixUser, index), Secret(generate(PrefixPassword, index)))

  /**
    * Generates a test key with the given index.
    *
    * @param index the index
    * @return the test key with this index
    */
  private def generateKey(index: Int): Key =
    Aes.keyFromString(generate(PrefixCryptPassword, index))

  /**
    * Generates a sequence of test data with a given length using the generator
    * function provided.
    *
    * @param count the number of elements to generate
    * @param gen   the generator function
    * @tparam A the type of elements to generate
    * @return the sequence with generated test data
    */
  private def generateSequence[A](count: Int)(gen: Int => A): IndexedSeq[A] =
    (1 to count) map gen

  /**
    * Generates a sequence with the given number of test realms with user
    * credentials.
    *
    * @param count the number of elements to generate
    * @return the sequence with test realms
    */
  private def generateTestRealms(count: Int): IndexedSeq[(String, UserCredentials)] =
    generateSequence(count) { index =>
      (generate(PrefixRealm, index), generateCredentials(index))
    }

  /**
    * Generates a sequence with the given number of test archives and their
    * encryption passwords.
    *
    * @param count the number of elements to generate
    * @return the sequence with test archives
    */
  private def generateLockData(count: Int): IndexedSeq[(String, Key)] =
    generateSequence(count) { index =>
      (generate(PrefixArchive, index), generateKey(index))
    }

  /**
    * Generates a sequence with messages to log into test realms.
    *
    * @param count the number of elements to generate
    * @return the sequence with login messages
    */
  private def generateLoginMessages(count: Int): IndexedSeq[LoginStateChanged] =
    generateTestRealms(count) map { t =>
      LoginStateChanged(t._1, Some(t._2))
    }

  /**
    * Generates a sequence with messages to unlock encrypted archives.
    *
    * @param count the number of elements to generate
    * @return the sequence with unlock messages
    */
  private def generateUnlockMessages(count: Int): IndexedSeq[LockStateChanged] =
    generateLockData(count) map { t =>
      LockStateChanged(t._1, Some(t._2))
    }

  /**
    * Generates a sequence with the given numbers of login and unlock messages.
    *
    * @param loginCount  the number of login messages
    * @param unlockCount the number of unlock messages
    * @return the sequence with these messages
    */
  private def generateStateMessages(loginCount: Int, unlockCount: Int): IndexedSeq[ArchiveStateChangedMessage] =
    generateLoginMessages(loginCount) ++ generateUnlockMessages(unlockCount)

  /**
    * Transforms the given list of state messages to a sequence that can be
    * compared. This is unfortunately not possible with the original messages
    * because the [[Secret]] class has no implementation of ''equals()''.
    * Therefore, messages containing a secret are converted to strings.
    *
    * @param messages the list of messages
    * @return the transformed list
    */
  private def comparableMessages(messages: Iterable[ArchiveStateChangedMessage]): Iterable[Any] =
    messages map:
      case LoginStateChanged(realm, Some(credentials)) =>
        s"LoginStateChanged($realm, {${credentials.userName}, ${credentials.password.secret})"
      case m => m

/**
  * Test class for ''SuperPasswordStorageService''.
  */
class SuperPasswordStorageServiceSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with BeforeAndAfterEach with Matchers with FileTestHelper with AsyncTestHelper:
  def this() = this(ActorSystem("SuperPasswordStorageServiceSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  override protected def afterEach(): Unit =
    tearDownTestFile()
    super.afterEach()

  import SuperPasswordStorageServiceSpec._
  import system.dispatcher

  /**
    * Invokes the service under test to write the given test data into a new
    * temporary file.
    *
    * @param realms   the test realms
    * @param lockData the test lock data
    * @param optPath  optional target path; is generated if None
    * @return a future with the path to the file that was written
    */
  private def writeFile(realms: Iterable[(String, UserCredentials)], lockData: Iterable[(String, Key)],
                        optPath: Option[Path] = None): Future[Path] =
    val path = optPath.getOrElse(createFileReference())
    SuperPasswordStorageServiceImpl.writeSuperPasswordFile(path, SuperPassword, realms, lockData)

  /**
    * Invokes the service under test to generate an encrypted file with the
    * lines produced by the passed in source. Using this function allows
    * testing files with arbitrary content.
    *
    * @param data the data source for the file to write
    * @return the path to the file that was written
    */
  private def writeFileWithContent(data: Source[ByteString, Any]): Path =
    val path = createFileReference()
    futureResult(SuperPasswordStorageServiceImpl.runEncryptStream(data, path, SuperPassword).map(_ => path))

  /**
    * Invokes the service under test to read the file with the given path.
    *
    * @param target the path of the test file
    * @return the future with the messages that have been loaded
    */
  private def readFile(target: Path): Future[Iterable[ArchiveStateChangedMessage]] =
    SuperPasswordStorageServiceImpl.readSuperPasswordFile(target, SuperPassword)

  "SuperPasswordStorageServiceImpl" should "support a round-trip with the super password file" in:
    val futMessages = for
      path <- writeFile(generateTestRealms(4), generateLockData(6))
      msg <- readFile(path)
    yield msg

    val messages = comparableMessages(futureResult(futMessages))
    messages should contain theSameElementsAs comparableMessages(generateStateMessages(4, 6))

  it should "encrypt the password file" in:
    val realms = generateTestRealms(8)
    val lockData = generateLockData(12)

    val path1 = futureResult(writeFile(realms, lockData))
    val path2 = futureResult(writeFile(realms, lockData))
    val data1 = readDataFile(path1)
    val data2 = readDataFile(path2)
    data1 should not be data2

  it should "handle an invalid entry" in:
    val line = ByteString("invalid,foo,bar\n")
    val path = writeFileWithContent(Source.single(line))

    expectFailedFuture[IllegalStateException](readFile(path))

  it should "handle a login entry with an unexpected number of fields" in:
    val line = ByteString("LOGIN,realm,user,password,additional")
    val path = writeFileWithContent(Source.single(line))

    expectFailedFuture[IllegalStateException](readFile(path))

  it should "handle an unlock entry with an unexpected number of fields" in:
    val line = ByteString("UNLOCK,archive")
    val path = writeFileWithContent(Source.single(line))

    expectFailedFuture[IllegalStateException](readFile(path))

  it should "handle a file that is not correctly encoded" in:
    val path = createDataFile()

    expectFailedFuture[IllegalStateException](readFile(path))

  it should "handle a file that is not correctly encrypted" in:
    val data =
      Base64.getEncoder.encode("This is Base64-encoded, but not encrypted.\n".getBytes(StandardCharsets.UTF_8))
    val line = ByteString(data)
    val path = writeFileWithContent(Source.single(line))

    expectFailedFuture[IllegalStateException](readFile(path))

  /**
    * Checks whether separator characters in specific strings are correctly
    * encoded.
    *
    * @param realmName the realm name
    * @param user      the user name
    * @param password  the password
    */
  private def checkLoginDecoding(realmName: String, user: String, password: String): Unit =
    val credentials = UserCredentials(user, Secret(password))
    val realms = generateTestRealms(3) ++ IndexedSeq((realmName, credentials))
    val expMessages = generateStateMessages(3, 0) ++
      IndexedSeq(LoginStateChanged(realmName, Some(credentials)))
    val futMessages = for
      path <- writeFile(realms, List.empty)
      msg <- readFile(path)
    yield msg

    val messages = comparableMessages(futureResult(futMessages))
    messages should contain theSameElementsAs comparableMessages(expMessages)

  it should "handle a separator character in a user name" in:
    checkLoginDecoding(PrefixRealm, "strange,user", "a_secret")

  it should "handle a separator character in a password" in:
    checkLoginDecoding(PrefixRealm, "theUser", "a,secret")

  it should "handle a separator character in a realm name" in:
    checkLoginDecoding("oh,my realm", PrefixUser, PrefixPassword)

  it should "handle a separator character in an archive name" in:
    val archiveName = "oh,my-archive"
    val archiveKey = generateKey(42)
    val lockData = generateLockData(3) ++ IndexedSeq((archiveName, archiveKey))
    val expMessages = generateStateMessages(0, 3) ++
      IndexedSeq(LockStateChanged(archiveName, Some(archiveKey)))
    val futMessages = for
      path <- writeFile(List.empty, lockData)
      msg <- readFile(path)
    yield msg

    val messages = futureResult(futMessages)
    messages should contain theSameElementsAs expMessages

  it should "handle a non-existing file when reading" in:
    expectFailedFuture[IOException](readFile(Paths.get("non-existing-file")))

  it should "handle a non-existing directory when writing" in:
    val target = Paths.get("path", "to", "non", "existing", "file")
    val futWrite = writeFile(generateTestRealms(1), generateLockData(1), Some(target))

    expectFailedFuture[IOException](futWrite)

  it should "override an existing super password file" in:
    val target = createDataFile()
    val futMessages = for
      path <- writeFile(generateTestRealms(1), generateLockData(1), Some(target))
      msg <- readFile(path)
    yield msg

    val messages = comparableMessages(futureResult(futMessages))
    messages should contain theSameElementsAs comparableMessages(generateStateMessages(1, 1))
