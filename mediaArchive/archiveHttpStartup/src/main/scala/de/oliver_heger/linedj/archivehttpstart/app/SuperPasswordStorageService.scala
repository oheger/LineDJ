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
import com.github.cloudfiles.crypt.service.CryptService
import de.oliver_heger.linedj.archivehttp.config.UserCredentials
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Concat, FileIO, Flow, Framing, Keep, Sink, Source}
import org.apache.pekko.stream.{IOOperationIncompleteException, IOResult}
import org.apache.pekko.util.ByteString

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.{Key, SecureRandom}
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.{ExecutionContext, Future}

/**
  * Definition of a service for reading and writing the encrypted file with
  * password information for all archives (the "super password file").
  *
  * For writing the file, the service gets passed information about the
  * archives available and their credentials, and (optionally) the passwords to
  * unlock them.
  *
  * A read operation yields a collection of messages that need to be published
  * on the system message bus in order to log into the archives and unlock
  * them.
  *
  * The file is always encrypted using the AES algorithm and the password
  * provided to the functions.
  */
trait SuperPasswordStorageService:
  /**
    * Writes an encrypted file containing the specified information about
    * realms and their credentials and archives and their unlock passwords.
    *
    * @param target        the path to the file to be written (an existing file
    *                      will be overwritten)
    * @param superPassword the password to encrypt the data
    * @param realms        a list of tuples with realm names and their credentials
    * @param lockData      a list of tuples with archive names and their passwords
    * @param system        the actor system
    * @return a future with the path to the file that has been written
    */
  def writeSuperPasswordFile(target: Path, superPassword: String, realms: Iterable[(String, UserCredentials)],
                             lockData: Iterable[(String, Key)])
                            (implicit system: ActorSystem): Future[Path]

  /**
    * Reads an encrypted file with information about realm credentials and
    * archive passwords. The resulting information is provided in form of
    * messages that need to be published on the message bus in order to access
    * the archives involved.
    *
    * @param target        the path to the file to be read
    * @param superPassword the password to decrypt the data
    * @param system        the actor system
    * @return a future with the information that was read
    */
  def readSuperPasswordFile(target: Path, superPassword: String)
                           (implicit system: ActorSystem): Future[Iterable[ArchiveStateChangedMessage]]

/**
  * The implementation of the [[SuperPasswordStorageService]] trait.
  */
object SuperPasswordStorageServiceImpl extends SuperPasswordStorageService:
  /** Prefix for an entry with login information in the password file. */
  private val LoginEntry = "LOGIN"

  /** Prefix for an entry to unlock an archive in the password file. */
  private val UnlockEntry = "UNLOCK"

  /** The separator between two entries in the password file. */
  private val EntrySeparator = "\n"

  /** The source of randomness. */
  private implicit val random: SecureRandom = new SecureRandom

  override def writeSuperPasswordFile(target: Path, superPassword: String,
                                      realms: Iterable[(String, UserCredentials)],
                                      lockData: Iterable[(String, Key)])
                                     (implicit system: ActorSystem): Future[Path] =
    val loginEntries = realms map realmDataToString
    val unlockEntries = lockData map { lock =>
      lockDataToString(lock)
    }
    val source = Source.combine(Source(loginEntries.toList), Source(unlockEntries.toList))(Concat(_))
      .map(ByteString(_))

    implicit val ec: ExecutionContext = system.dispatcher
    runEncryptStream(source, target, superPassword)
      .map(_ => target)
      .recoverWith {
        case e: IOOperationIncompleteException =>
          Future.failed(new IOException(e))
      }(system.dispatcher)

  override def readSuperPasswordFile(target: Path, superPassword: String)
                                    (implicit system: ActorSystem): Future[Iterable[ArchiveStateChangedMessage]] =
    val source = decryptSource(target, superPassword)
    val sink = Sink.fold[List[ArchiveStateChangedMessage],
      ArchiveStateChangedMessage](List.empty[ArchiveStateChangedMessage]) { (lst, msg) => msg :: lst }
    source.map { line =>
      val fields = line.utf8String.split(",")
      fields.head match
        case LoginEntry =>
          createLoginStateChanged(fields)
        case UnlockEntry =>
          createLockStateChanged(fields)
        case e => throw new IllegalStateException("Unsupported entry: " + e)
    }.runWith(sink)

  /**
    * Runs a stream that processes the provided data source and writes its
    * content in encrypted form to a file. (This is package local for better
    * testability.)
    *
    * @param data          the source with the data to be encrypted
    * @param target        path to the file to be written
    * @param superPassword the password to encrypt the file
    * @return a ''Future'' with the result of stream processing
    */
  private[archivehttpstart] def runEncryptStream(data: Source[ByteString, Any], target: Path, superPassword: String)
                                                (implicit system: ActorSystem): Future[IOResult] =
    val cryptSource = CryptService.encryptSource(Aes, Aes.keyFromString(superPassword), data)
    val sink = Flow[ByteString].map { bs =>
      ByteString(Base64.getEncoder.encodeToString(bs.toArray) + EntrySeparator)
    }.toMat(FileIO.toPath(target))(Keep.right)
    cryptSource.runWith(sink)


  /**
    * Generates a source for reading the lines of the encrypted password file.
    * The source emits the decrypted lines.
    *
    * @param target        path to the file to read
    * @param superPassword the password to decrypt the file
    * @return the ''Source'' to read and decrypt the file
    */
  private[archivehttpstart] def decryptSource(target: Path, superPassword: String):
  Source[ByteString, Any] =
    CryptService.decryptSource(Aes, Aes.keyFromString(superPassword),
      FileIO.fromPath(target)
        .via(Framing.delimiter(ByteString(EntrySeparator), 1024))
        .map { line =>
          ByteString(decodeBase64(line.toArray))
        })

  /**
    * Converts the given data for a realm to a string to be written into the
    * password file.
    *
    * @param realm the tuple with realm information
    * @return the string for this realm
    */
  private def realmDataToString(realm: (String, UserCredentials)): String =
    val realmEnc = encodeBase64(realm._1)
    val userEnc = encodeBase64(realm._2.userName)
    val pwdEnc = encodeBase64(realm._2.password.secret)
    s"$LoginEntry,$realmEnc,$userEnc,$pwdEnc"

  /**
    * Converts the given data for unlocking an archive to a string to be
    * written to the password file.
    *
    * @param lock the tuple with information about unlocking an archive
    * @return
    */
  private def lockDataToString(lock: (String, Key)): String =
    val archiveEnc = encodeBase64(lock._1)
    val keyEnc = encodeKey(lock._2)
    s"$UnlockEntry,$archiveEnc,$keyEnc"

  /**
    * Creates a ''LoginStateChanged'' message from the values provided.
    *
    * @param fields the array with the data
    * @return the resulting ''LoginStateChanged''
    */
  private def createLoginStateChanged(fields: Array[String]): LoginStateChanged =
    checkFieldCount(fields, 4)
    val realmDec = decodeBase64Str(fields(1))
    val userDec = decodeBase64Str(fields(2))
    val pwdDec = decodeBase64Str(fields(3))
    LoginStateChanged(realmDec, Some(UserCredentials(userDec, Secret(pwdDec))))

  /**
    * Create a ''LockStateChanged'' message from the values provided.
    *
    * @param fields the array with the data
    * @return the resulting ''LockStateChanged''
    */
  private def createLockStateChanged(fields: Array[String]): LockStateChanged =
    checkFieldCount(fields, 3)
    val archiveDec = decodeBase64Str(fields(1))
    LockStateChanged(archiveDec, Some(decodeKey(fields(2))))

  /**
    * Checks whether the given array has the expected number of elements.
    * Throws an exception if this is not the case.
    *
    * @param array    the array to check
    * @param expCount the expected number of elements
    */
  private def checkFieldCount(array: Array[String], expCount: Int): Unit =
    if array.length != expCount then
      throw new IllegalStateException(s"Expected $expCount fields in line, but got ${array.length}.")

  /**
    * Returns a Base64-encoded form of the given key.
    *
    * @param key the key to encode
    * @return the encoded form of this key
    */
  private def encodeKey(key: Key): String =
    Base64.getEncoder.encodeToString(key.getEncoded)

  /**
    * Constructs a key from the passed in Base64-encoded data.
    *
    * @param encKeyData the encoded data of the key
    * @return the resulting key
    */
  private def decodeKey(encKeyData: String): Key =
    val decodedData = Base64.getDecoder.decode(encKeyData)
    new SecretKeySpec(decodedData, Aes.AlgorithmName)

  /**
    * Decodes data with the Base64 decoder with error handling.
    *
    * @param data the data to be decoded
    * @return the decoded data
    */
  private def decodeBase64(data: Array[Byte]): Array[Byte] =
    try
      Base64.getDecoder.decode(data)
    catch
      case e: IllegalArgumentException =>
        throw new IllegalStateException("Invalid file content: Not Base64-encoded.", e)

  /**
    * Convenience function to decode a Base64 string.
    *
    * @param s the string to decode
    * @return the decoded string
    */
  private def decodeBase64Str(s: String): String =
    new String(decodeBase64(s.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8)

  /**
    * Convenience function to encode a string in Base64.
    *
    * @param s the string to encode
    * @return the encoded string
    */
  private def encodeBase64(s: String): String =
    Base64.getEncoder.encodeToString(s.getBytes(StandardCharsets.UTF_8))
