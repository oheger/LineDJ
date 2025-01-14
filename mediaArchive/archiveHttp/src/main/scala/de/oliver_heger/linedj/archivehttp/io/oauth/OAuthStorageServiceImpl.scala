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

package de.oliver_heger.linedj.archivehttp.io.oauth

import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.OAuthTokenData
import com.github.cloudfiles.crypt.alg.aes.Aes
import com.github.cloudfiles.crypt.service.CryptService
import de.oliver_heger.linedj.archivehttp.config.OAuthStorageConfig
import org.apache.commons.configuration.XMLConfiguration
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.IOResult
import org.apache.pekko.stream.scaladsl.{FileIO, Sink, Source}
import org.apache.pekko.util.ByteString

import java.io.StringReader
import java.security.SecureRandom
import scala.concurrent.{ExecutionContext, Future}

/**
  * A default implementation of the [[OAuthStorageService]] trait.
  *
  * This implementation stores the data related to an OAuth identity provider
  * in files with the same base name, but different suffixes. Sensitive
  * information can be encrypted if a password is provided.
  */
object OAuthStorageServiceImpl extends OAuthStorageService[OAuthStorageConfig, OAuthConfig, Secret, OAuthTokenData]:
  /** Constant for the suffix used for the file with the OAuth config. */
  final val SuffixConfigFile = ".xml"

  /** Constant for the suffix used for the file with the client secret. */
  final val SuffixSecretFile = ".sec"

  /** Constant for the suffix of the file with token information. */
  final val SuffixTokenFile = ".toc"

  /** Property for the client ID in the persistent config representation. */
  final val PropClientId = "client-id"

  /**
    * Property for the authorization endpoint in the persistent config
    * representation.
    */
  final val PropAuthorizationEndpoint = "authorization-endpoint"

  /**
    * Property for the token endpoint in the persistent config
    * representation.
    */
  final val PropTokenEndpoint = "token-endpoint"

  /** Property for the scope in the persistent config representation. */
  final val PropScope = "scope"

  /** Property for the redirect URI in the persistent config representation. */
  final val PropRedirectUri = "redirect-uri"

  /** The separator character used within the token file. */
  private val TokenSeparator = "\t"

  /** The secure random object. */
  private implicit val secRandom: SecureRandom = new SecureRandom

  override def loadConfig(storageConfig: OAuthStorageConfig)
                         (implicit ec: ExecutionContext, system: ActorSystem): Future[OAuthConfig] =
    loadAndMapFile(storageConfig, SuffixConfigFile) { buf =>
      val reader = new StringReader(buf.utf8String)
      val config = new XMLConfiguration
      config.setThrowExceptionOnMissing(true)
      config.load(reader)

      OAuthConfig(clientID = config.getString(PropClientId),
        authorizationEndpoint = config.getString(PropAuthorizationEndpoint),
        tokenEndpoint = config.getString(PropTokenEndpoint),
        scope = config.getString(PropScope),
        redirectUri = config.getString(PropRedirectUri))
    }

  override def loadClientSecret(storageConfig: OAuthStorageConfig)
                               (implicit ec: ExecutionContext, system: ActorSystem): Future[Secret] =
    loadAndMapFile(storageConfig, SuffixSecretFile, Some(storageConfig.encPassword))(buf => Secret(buf.utf8String))

  override def loadTokens(storageConfig: OAuthStorageConfig)
                         (implicit ec: ExecutionContext, system: ActorSystem): Future[OAuthTokenData] =
    loadAndMapFile(storageConfig, SuffixTokenFile, optPwd = Some(storageConfig.encPassword)) { buf =>
      val parts = buf.utf8String.split(TokenSeparator)
      if parts.length < 2 then
        throw new IllegalArgumentException(s"Token file for ${storageConfig.baseName} contains too few tokens.")
      else if parts.length > 2 then
        throw new IllegalArgumentException(s"Token file for ${storageConfig.baseName} has unexpected content.")
      OAuthTokenData(accessToken = parts(0), refreshToken = parts(1))
    }

  /**
    * Returns a source for loading a file based on the given storage
    * configuration.
    *
    * @param storageConfig the storage configuration
    * @param suffix        the suffix of the file to be loaded
    * @return the source for loading this file
    */
  private def fileSource(storageConfig: OAuthStorageConfig, suffix: String): Source[ByteString, Future[IOResult]] =
    FileIO.fromPath(storageConfig.resolveFileName(suffix))

  /**
    * Loads a file based on a given storage configuration into memory, applies
    * a mapping function to the loaded data, and returns the result. If a
    * password is provided, the loaded data is decrypted.
    *
    * @param storageConfig the storage configuration
    * @param suffix        the suffix of the file to be loaded
    * @param optPwd        an optional password for decryption
    * @param f             the mapping function
    * @param ec            the execution context
    * @param system        the actor system to materialize streams
    * @tparam T the type of the result
    * @return the result generated by the mapping function
    */
  private def loadAndMapFile[T](storageConfig: OAuthStorageConfig, suffix: String, optPwd: Option[Secret] = None)
                               (f: ByteString => T)
                               (implicit ec: ExecutionContext, system: ActorSystem): Future[T] =
    val file = fileSource(storageConfig, suffix)
    val source = optPwd.map(pwd => cryptSource(file, pwd)) getOrElse file
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    source.runWith(sink).map(f)

  /**
    * Returns a source that decrypts the passed in source. This is used to
    * load files with sensitive information.
    *
    * @param source the original source
    * @param secret the password for decryption
    * @return the decorated source
    */
  private def cryptSource[Mat](source: Source[ByteString, Mat], secret: Secret): Source[ByteString, Mat] =
    val key = Aes.keyFromString(secret.secret)
    CryptService.decryptSource(Aes, key, source)
