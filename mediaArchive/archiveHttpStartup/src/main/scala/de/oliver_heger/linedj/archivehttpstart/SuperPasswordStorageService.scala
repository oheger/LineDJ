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

package de.oliver_heger.linedj.archivehttpstart

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.{Key, SecureRandom}
import java.util.Base64

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{IOOperationIncompleteException, IOResult}
import akka.stream.scaladsl.{Concat, FileIO, Flow, Framing, Keep, Sink, Source}
import akka.util.ByteString
import de.oliver_heger.linedj.archivehttp.config.UserCredentials
import de.oliver_heger.linedj.crypt.{CryptStage, KeyGenerator, Secret}

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
  */
trait SuperPasswordStorageService {
  /**
    * Writes an encrypted file containing the specified information about
    * realms and their credentials and archives and their unlock passwords.
    *
    * @param target        the path to the file to be written (an existing file
    *                      will be overwritten)
    * @param keyGenerator  the generator for keys
    * @param superPassword the password to encrypt the data
    * @param realms        a list of tuples with realm names and their credentials
    * @param lockData      a list of tuples with archive names and their passwords
    * @param system        the actor system
    * @return a future with the path to the file that has been written
    */
  def writeSuperPasswordFile(target: Path, keyGenerator: KeyGenerator, superPassword: String,
                             realms: Iterable[(String, UserCredentials)], lockData: Iterable[(String, Key)])
                            (implicit system: ActorSystem): Future[Path]

  /**
    * Reads an encrypted file with information about realm credentials and
    * archive passwords. The resulting information is provided in form of
    * messages that need to be published on the message bus in order to access
    * the archives involved.
    *
    * @param target        the path to the file to be read
    * @param keyGenerator  the generator for keys
    * @param superPassword the password to decrypt the data
    * @param system        the actor system
    * @return a future with the information that was read
    */
  def readSuperPasswordFile(target: Path, keyGenerator: KeyGenerator, superPassword: String)
                           (implicit system: ActorSystem): Future[Iterable[ArchiveStateChangedMessage]]
}

/**
  * The implementation of the [[SuperPasswordStorageService]] trait.
  */
object SuperPasswordStorageServiceImpl extends SuperPasswordStorageService {
  /** Prefix for an entry with login information in the password file. */
  private val LoginEntry = "LOGIN"

  /** Prefix for an entry to unlock an archive in the password file. */
  private val UnlockEntry = "UNLOCK"

  /** The separator between two entries in the password file. */
  private val EntrySeparator = "\n"

  override def writeSuperPasswordFile(target: Path, keyGenerator: KeyGenerator, superPassword: String,
                                      realms: Iterable[(String, UserCredentials)],
                                      lockData: Iterable[(String, Key)])
                                     (implicit system: ActorSystem): Future[Path] = {
    val loginEntries = realms map realmDataToString
    val unlockEntries = lockData map { lock =>
      lockDataToString(keyGenerator, lock)
    }
    val source = Source.combine(Source(loginEntries.toList), Source(unlockEntries.toList))(Concat(_))
    val sink = encryptSink(target, keyGenerator, superPassword)

    implicit val ec: ExecutionContext = system.dispatcher
    source
      .map(ByteString(_))
      .runWith(sink)
      .map(_ => target)
      .recoverWith {
        case e: IOOperationIncompleteException =>
          Future.failed(new IOException(e))
      }(system.dispatcher)
  }

  override def readSuperPasswordFile(target: Path, keyGenerator: KeyGenerator, superPassword: String)
                                    (implicit system: ActorSystem): Future[Iterable[ArchiveStateChangedMessage]] = {
    val source = decryptSource(target, keyGenerator, superPassword)
    val sink = Sink.fold[List[ArchiveStateChangedMessage],
      ArchiveStateChangedMessage](List.empty[ArchiveStateChangedMessage]) { (lst, msg) => msg :: lst }
    source.map { line =>
      val fields = line.utf8String.split(",")
      fields.head match {
        case LoginEntry =>
          createLoginStateChanged(fields)
        case UnlockEntry =>
          createLockStateChanged(keyGenerator, fields)
        case e => throw new IllegalStateException("Unsupported entry: " + e)
      }
    }.runWith(sink)
  }

  /**
    * Generates a sink that expects ''ByteString'' objects and writes them in
    * encrypted form to a file. (This is package local for better testability.)
    *
    * @param target        path to the file to be written
    * @param keyGenerator  the key generator
    * @param superPassword the password to encrypt the file
    * @return the ''Sink'' for writing encrypted strings to the file
    */
  private[archivehttpstart] def encryptSink(target: Path, keyGenerator: KeyGenerator, superPassword: String):
  Sink[ByteString, Future[IOResult]] =
    Flow[ByteString].via(CryptStage.encryptStage(keyGenerator.generateKey(superPassword), new SecureRandom))
      .map { bs =>
        ByteString(Base64.getEncoder.encodeToString(bs.toArray) + EntrySeparator)
      }.toMat(FileIO.toPath(target))(Keep.right)

  /**
    * Generates a source for reading the lines of the encrypted password file.
    * The source emits the decrypted lines.
    *
    * @param target        path to the file to read
    * @param keyGenerator  the key generator
    * @param superPassword the password to decrypt the file
    * @return the ''Source'' to read and decrypt the file
    */
  private[archivehttpstart] def decryptSource(target: Path, keyGenerator: KeyGenerator, superPassword: String):
  Source[ByteString, NotUsed] =
    FileIO.fromPath(target)
      .via(Framing.delimiter(ByteString(EntrySeparator), 1024))
      .map { line =>
        ByteString(decodeBase64(line.toArray))
      }
      .viaMat(CryptStage.decryptStage(keyGenerator.generateKey(superPassword), new SecureRandom))(Keep.right)

  /**
    * Converts the given data for a realm to a string to be written into the
    * password file.
    *
    * @param realm the tuple with realm information
    * @return the string for this realm
    */
  private def realmDataToString(realm: (String, UserCredentials)): String = {
    val realmEnc = encodeBase64(realm._1)
    val userEnc = encodeBase64(realm._2.userName)
    val pwdEnc = encodeBase64(realm._2.password.secret)
    s"$LoginEntry,$realmEnc,$userEnc,$pwdEnc"
  }

  /**
    * Converts the given data for unlocking an archive to a string to be
    * written to the password file.
    *
    * @param keyGenerator the key generator
    * @param lock         the tuple with information about unlocking an archive
    * @return
    */
  private def lockDataToString(keyGenerator: KeyGenerator, lock: (String, Key)): String = {
    val archiveEnc = encodeBase64(lock._1)
    val keyEnc = new String(keyGenerator.encodeKey(lock._2), StandardCharsets.UTF_8)
    s"$UnlockEntry,$archiveEnc,$keyEnc"
  }

  /**
    * Creates a ''LoginStateChanged'' message from the values provided.
    *
    * @param fields the array with the data
    * @return the resulting ''LoginStateChanged''
    */
  private def createLoginStateChanged(fields: Array[String]): LoginStateChanged = {
    checkFieldCount(fields, 4)
    val realmDec = decodeBase64Str(fields(1))
    val userDec = decodeBase64Str(fields(2))
    val pwdDec = decodeBase64Str(fields(3))
    LoginStateChanged(realmDec, Some(UserCredentials(userDec, Secret(pwdDec))))
  }

  /**
    * Create a ''LockStateChanged'' message from the values provided.
    *
    * @param keyGenerator the key generator
    * @param fields       the array with the data
    * @return the resulting ''LockStateChanged''
    */
  private def createLockStateChanged(keyGenerator: KeyGenerator, fields: Array[String]): LockStateChanged = {
    checkFieldCount(fields, 3)
    val archiveDec = decodeBase64Str(fields(1))
    LockStateChanged(archiveDec, Some(keyGenerator.decodeKey(fields(2).getBytes(StandardCharsets.UTF_8))))
  }

  /**
    * Checks whether the given array has the expected number of elements.
    * Throws an exception if this is not the case.
    *
    * @param array    the array to check
    * @param expCount the expected number of elements
    */
  private def checkFieldCount(array: Array[String], expCount: Int): Unit = {
    if (array.length != expCount) {
      throw new IllegalStateException(s"Expected $expCount fields in line, but got ${array.length}.")
    }
  }

  /**
    * Decodes data with the Base64 decoder with error handling.
    *
    * @param data the data to be decoded
    * @return the decoded data
    */
  private def decodeBase64(data: Array[Byte]): Array[Byte] =
    try {
      Base64.getDecoder.decode(data)
    } catch {
      case e: IllegalArgumentException =>
        throw new IllegalStateException("Invalid file content: Not Base64-encoded.", e)
    }

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
}
