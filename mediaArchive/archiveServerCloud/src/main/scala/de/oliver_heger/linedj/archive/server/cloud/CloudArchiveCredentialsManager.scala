/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.archive.server.cloud

import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.crypt.alg.aes.Aes
import com.github.cloudfiles.crypt.service.CryptService
import de.oliver_heger.linedj.archive.cloud.auth.Credentials
import de.oliver_heger.linedj.archive.server.cloud.CloudArchiveCredentialsManager.{CredentialKey, CredentialKeyType, FileCredentialPrefix, SetCredentialsResult, readCredentials}
import de.oliver_heger.linedj.io.LocalFsUtils
import de.oliver_heger.linedj.io.parser.JsonStreamParser
import de.oliver_heger.linedj.shared.actors.ActorFactory
import de.oliver_heger.linedj.shared.actors.ActorFactory.given
import org.apache.logging.log4j.LogManager
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{FileIO, Sink, Source}
import org.apache.pekko.util.{ByteString, Timeout}
import spray.json.*
import spray.json.DefaultJsonProtocol.*

import java.nio.file.Path
import java.security.SecureRandom
import scala.concurrent.Future
import scala.util.{Failure, Success}

object CloudArchiveCredentialsManager:
  /** The extension for unencrypted files storing credentials. */
  private val PlainCredentialsFileExtension = "json"

  /** The extension for encrypted files storing credentials. */
  private val CryptCredentialsFileExtension = "json.crypt"

  /** The suffix to detect an encrypted file based on its extension. */
  private val CryptCredentialsFileSuffix = "." + CryptCredentialsFileExtension

  /** A prefix for credential keys for encrypted files. */
  private val FileCredentialPrefix = "file://"

  /**
    * A set with the extensions of file to search for in the configured
    * directory with credentials files.
    */
  private val FileExtensions = Set(PlainCredentialsFileExtension, CryptCredentialsFileExtension)

  /** The logger. */
  private val log = LogManager.getLogger(classOf[CloudArchiveCredentialsManager])

  /**
    * An enumeration class that defines several types of keys for credentials.
    * [[CloudArchiveCredentialsManager]] has to deal with credentials for
    * different purposes: there are credentials required to access cloud
    * archives, and credentials to decrypt files that contain other
    * credentials. This enumeration is intended to be used by clients that
    * prompt end users for credential values, so that they can filter for
    * specific types or provide additional information about the keys when
    * querying users for their values.
    */
  enum CredentialKeyType:
    /**
      * The key type for credentials required to access cloud archives. Secrets
      * falling into this category are usernames, passwords, and secrets to
      * decrypt the content of archives.
      */
    case Archive

    /**
      * The key type for secrets to decrypt credential files. Such secrets are
      * kind of master passwords that typically make a number of secrets of
      * type [[Archive]] available.
      */
    case File

  /**
    * A data class storing information about a single credential key that
    * needs to be provided to gain access to a cloud archive. Typically, end
    * users have to enter the secret values for these keys, so that data can be
    * loaded from cloud archives.
    *
    * @param key     the name of the key
    * @param keyType the type of this key
    */
  final case class CredentialKey(key: String,
                                 keyType: CredentialKeyType)

  /**
    * A data class serving as the result of a ''setCredentials'' operation.
    * When setting credentials, only those keys are processed which have been
    * requested by consumers (which are pending to be set). An instance of this
    * class holds the information how many credentials have been passed and
    * which of those have not been pending and could therefore not be
    * processed. This gives the caller feedback about invalid credentials.
    *
    * @param totalCredentialsCount the total number of passed in credentials
    * @param invalidKeys           a [[Set]] with the keys that were not pending and thus
    *                              rejected
    */
  final case class SetCredentialsResult(totalCredentialsCount: Int,
                                        invalidKeys: Set[String])

  /** Constant for an initial, empty result of a set credentials operation. */
  private val EmptySetCredentialsResult = SetCredentialsResult(0, Set.empty)

  /**
    * An internal data class modeling credential information in a JSON
    * structure. This class is used to deserialize credential data from files
    * and credential requests.
    *
    * @param key   the key of the credential
    * @param value the (secret) value
    */
  private case class Credential(key: String, value: String)

  /** The format to deserialize [[Credential]] structures. */
  private given credentialFormat: RootJsonFormat[Credential] = jsonFormat2(Credential.apply)

  /** The secure random for decrypt operations. */
  private given random: SecureRandom = SecureRandom()

  /**
    * Creates a new instance of [[CloudArchiveCredentialsManager]] that
    * searches for credential files in the specified directory.
    *
    * @param credentialDirectory  the directory storing credential files
    * @param factory              the actor factory
    * @param credentialsActorName the name of the credential manager actor
    * @param timeout              the timeout for ask operations
    * @return the new [[CloudArchiveCredentialsManager]] object
    */
  def apply(credentialDirectory: Path,
            factory: ActorFactory,
            credentialsActorName: String = Credentials.CredentialsManagerName)
           (using timeout: Timeout): CloudArchiveCredentialsManager =
    given ActorSystem = factory.actorSystem

    val (setter, resolver) = Credentials.setUpCredentialsManager(factory, credentialsActorName)

    val futInit = LocalFsUtils.listFolder(credentialDirectory, factory.actorSystem, FileExtensions) map : files =>
      files.foreach: file =>
        processCredentialsFile(file, setter, resolver)

    futInit.onComplete:
      case Success(files) =>
        log.info("Credentials directory has been processed.")
      case Failure(exception) =>
        log.error("Could not load credential files from directory '{}'.", credentialDirectory, exception)

    new CloudArchiveCredentialsManager(resolver, setter, futInit.map(_ => Done))

  /**
    * Processes a file with credentials that has been found in the credential
    * directory. The file is parsed, and the contained credentials are passed
    * to the setter.
    *
    * @param credentialsFile  the path to the credentials file
    * @param credentialSetter the object to set credentials
    * @param resolver         the function to resolve credentials
    * @param system           the actor system
    */
  private def processCredentialsFile(credentialsFile: Path,
                                     credentialSetter: Credentials.CredentialSetter,
                                     resolver: Credentials.ResolverFunc)
                                    (using system: ActorSystem): Unit =
    log.info("Found credentials file '{}'.", credentialsFile.getFileName)
    if credentialsFile.getFileName.toString.endsWith(CryptCredentialsFileSuffix) then
      processEncryptedCredentialsFile(credentialsFile, credentialSetter, resolver)
    else
      processPlainCredentialsFile(credentialsFile, credentialSetter)

  /**
    * Processes an unencrypted file with credentials. The file can be read
    * directly.
    *
    * @param credentialsFile  the path to the credentials file
    * @param credentialSetter the object to set credentials
    * @param system           the actor system
    */
  private def processPlainCredentialsFile(credentialsFile: Path, credentialSetter: Credentials.CredentialSetter)
                                         (using system: ActorSystem): Unit =
    val source = FileIO.fromPath(credentialsFile)
    logReadOutcome(credentialsFile, readCredentials(source, credentialSetter))

  /**
    * Processes an encrypted files with credentials. The function requests a
    * secret with a key derived from the file name from the resolver function.
    * When this secret becomes available, the file can be read. In case that
    * the secret is invalid, the function makes sure that it is possible to set
    * the credential again by removing the invalid secret and registering
    * another listener for it.
    *
    * @param credentialsFile   the path to the credentials file
    * @param credentialsSetter the object to set credentials
    * @param resolver          the resolver function
    * @param system            the actor system
    */
  private def processEncryptedCredentialsFile(credentialsFile: Path,
                                              credentialsSetter: Credentials.CredentialSetter,
                                              resolver: Credentials.ResolverFunc)
                                             (using system: ActorSystem): Unit =
    val fileKey = FileCredentialPrefix + credentialsFile.getFileName.toString.stripSuffix(CryptCredentialsFileSuffix)
    resolver(fileKey).map: secret =>
      credentialsSetter.clearCredential(fileKey) // Clear directly, in case the secret is invalid.
      log.info("Got secret to decrypt credentials file '{}'.", credentialsFile)

      val fileSource = FileIO.fromPath(credentialsFile)
      val key = Aes.keyFromString(secret.secret)
      val source = CryptService.decryptSource(Aes, key, fileSource)
      logReadOutcome(credentialsFile, readCredentials(source, credentialsSetter)).onComplete:
        case Success(_) =>
        case Failure(exception) =>
          log.warn("Waiting again for credential '{}'.", fileKey)
          processEncryptedCredentialsFile(credentialsFile, credentialsSetter, resolver)

  /**
    * Reads the content of a credentials file from a given source, passes the
    * found credentials to a [[Credentials.CredentialSetter]], and logs the
    * outcome of the operation.
    *
    * @param credentialsFile  the path to the credentials file
    * @param credentialSetter the object to set credentials
    * @param source           the source to the content of the file
    * @param system           the actor system
    */
  private def readCredentialsFile(credentialsFile: Path,
                                  credentialSetter: Credentials.CredentialSetter,
                                  source: Source[ByteString, Any])
                                 (using system: ActorSystem): Unit =
    readCredentials(source, credentialSetter).onComplete:
      case Success(_) =>
        log.info("Successfully loaded credentials from '{}'.", credentialsFile.getFileName)
      case Failure(exception) =>
        log.error("Failed to process credentials file '{}'.", credentialsFile.getFileName, exception)

  /**
    * Extends the [[Future]] for reading a credentials file with logging about
    * the outcome of the operation. This generates log statements with the file
    * name and either a success message or an exception stacktrace.
    *
    * @param credentialsFile the path to the credentials file
    * @param futRead         the [[Future]] for the read operation
    * @param system          the actor system
    * @return the extended [[Future]]
    * @tparam T the type of the [[Future]]
    */
  private def logReadOutcome[T](credentialsFile: Path, futRead: Future[T])
                               (using system: ActorSystem): Future[T] =
    futRead.andThen:
      case Success(_) =>
        log.info("Successfully loaded credentials from '{}'.", credentialsFile.getFileName)
      case Failure(exception) =>
        log.error("Failed to process credentials file '{}'.", credentialsFile.getFileName, exception)

  /**
    * Parses the given [[Source]] with credential information in JSON format
    * and passes the found credentials to a [[Credentials.CredentialSetter]].
    *
    * @param source           the source containing credential information
    * @param credentialSetter the object to set credentials
    * @param system           the actor system
    * @return a [[Future]] with the result
    */
  private def readCredentials(source: Source[ByteString, Any], credentialSetter: Credentials.CredentialSetter)
                             (using system: ActorSystem): Future[SetCredentialsResult] =
    credentialSetter.credentialKeys flatMap : keys =>
      val validKeys = keys.filter(_.pending).map(_.key)
      val setterSink = Sink.fold[SetCredentialsResult, Credential](EmptySetCredentialsResult): (res, cred) =>
        val fileKey = FileCredentialPrefix + cred.key
        val key = if validKeys.contains(fileKey) then fileKey else cred.key
        val nextInvalidKeys = if validKeys.contains(key) then
          credentialSetter.setCredential(key, Secret(cred.value))
          res.invalidKeys
        else
          res.invalidKeys + cred.key
        SetCredentialsResult(res.totalCredentialsCount + 1, nextInvalidKeys)
      JsonStreamParser.parseStream[Credential, Any](source).runWith(setterSink)
end CloudArchiveCredentialsManager

/**
  * A class to manage the credentials required for accessing cloud archives.
  *
  * This class expects the credentials for cloud archives to be defined in JSON
  * files located in a configurable directory. The JSON must contain a list
  * with objects defining key/value-pairs in the following form:
  * {{{
  * [
  *   {
  *     "key": "myArchiveUser",
  *     "value": "scott"
  *   },
  *   {
  *     "key": myArchivePassword",
  *     "value": "tiger"
  *   },
  *   ...
  * ]
  * }}}
  *
  * On startup, the class scans the configured directory for files with the
  * following extensions:
  *  - *.json are files defining credentials in the format above in plain text.
  *  - *.json.crypt are files following the format above, but are encrypted
  *    (which is the recommended option).
  *
  * This class wraps a credential manager provided by the [[Credentials]]
  * module. It allows setting credentials and querying the ones that are
  * currently pending. For all encrypted files that were encountered, the class
  * registers itself as consumer for a credential with a key corresponding to
  * the file name (without the extension). This credential is interpreted as
  * the key to decrypt the file. So, when it is set, the class attempts to
  * decrypt the file and parse it. It passes all keys found in such files to
  * the credential management.
  *
  * @param resolverFunc the function to resolve secret values
  * @param setter       the object to set credentials
  * @param initFuture   a [[Future]] that completes when this instance has been
  *                     initialized; this can be used to check when the
  *                     instance is ready and whether initialization was
  *                     successful
  * @param system       the actor system
  */
class CloudArchiveCredentialsManager(val resolverFunc: Credentials.ResolverFunc,
                                     setter: Credentials.CredentialSetter,
                                     val initFuture: Future[Done])
                                    (using system: ActorSystem):
  /**
    * Sets credentials at the wrapped credentials manager. This function
    * expects a [[Source]] with data in JSON format as described in the class
    * comment. It parses the data and extracts the credentials. The idea is
    * that this function is called with the entity of a client request that
    * provides credentials to log into cloud archives.
    *
    * From the parsed credentials, only those are processed for which consumers
    * are waiting. These are valid keys, others are ignored. The return value
    * of this function contains information about the credentials that were
    * rejected.
    *
    * @param data the [[Source]] with credential information
    * @return a [[Future]] with the result of the operation
    */
  def setCredentials(data: Source[ByteString, Any]): Future[SetCredentialsResult] =
    readCredentials(data, setter)

  /**
    * Returns a [[Set]] with information about the keys of credentials that are
    * currently pending to be set. These are valid keys that could be set via
    * [[setCredentials]]. The type of the keys is provided as well.
    *
    * @return a [[Future]] with the keys of pending credentials
    */
  def pendingCredentials: Future[Set[CredentialKey]] =
    setter.credentialKeys map : keyInfo =>
      keyInfo.filter(_.pending).map: key =>
        val credentialType = if key.key.startsWith(FileCredentialPrefix) then CredentialKeyType.File
        else CredentialKeyType.Archive
        CredentialKey(key.key.stripPrefix(FileCredentialPrefix), credentialType)
