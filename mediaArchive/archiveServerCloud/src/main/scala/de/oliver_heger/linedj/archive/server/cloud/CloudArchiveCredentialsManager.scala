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
import de.oliver_heger.linedj.archive.cloud.auth.Credentials
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
import scala.concurrent.Future
import scala.util.{Failure, Success}

object CloudArchiveCredentialsManager:
  /** The extension for unencrypted files storing credentials. */
  private val PlainCredentialsFileExtension = "json"

  /** The logger. */
  private val log = LogManager.getLogger(classOf[CloudArchiveCredentialsManager])

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

    LocalFsUtils.listFolder(credentialDirectory, factory.actorSystem, Set(PlainCredentialsFileExtension))
      .onComplete:
        case Success(files) =>
          files.foreach: file =>
            processCredentialsFile(file, setter)
        case Failure(exception) =>
          log.error("Could not load credential files from directory '{}'.", credentialDirectory, exception)

    new CloudArchiveCredentialsManager(resolver)

  /**
    * Processes a file with credentials that has been found in the credential
    * directory. The file is parsed, and the contained credentials are passed
    * to the setter.
    *
    * @param credentialsFile  the path to the credentials file
    * @param credentialSetter the object to set credentials
    * @param system           the actor system
    */
  private def processCredentialsFile(credentialsFile: Path, credentialSetter: Credentials.CredentialSetter)
                                    (using system: ActorSystem): Unit =
    log.info("Found credentials file '{}'.", credentialsFile.getFileName)
    val source = FileIO.fromPath(credentialsFile)
    readCredentials(source, credentialSetter).onComplete:
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
                             (using system: ActorSystem): Future[Done] =
    val setterSink = Sink.foreach[Credential](c => credentialSetter.setCredential(c.key, Secret(c.value)))
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
  */
class CloudArchiveCredentialsManager(val resolverFunc: Credentials.ResolverFunc)
