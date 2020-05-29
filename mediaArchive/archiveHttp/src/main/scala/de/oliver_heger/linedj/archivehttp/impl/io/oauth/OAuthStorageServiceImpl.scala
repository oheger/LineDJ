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

package de.oliver_heger.linedj.archivehttp.impl.io.oauth

import java.security.SecureRandom

import akka.actor.ActorSystem
import akka.stream.IOResult
import akka.stream.scaladsl.{FileIO, Sink, Source}
import akka.util.ByteString
import de.oliver_heger.linedj.archivehttp.config.OAuthStorageConfig
import de.oliver_heger.linedj.archivehttp.crypt.{AESKeyGenerator, Secret}
import de.oliver_heger.linedj.archivehttp.impl.crypt.CryptService

import scala.concurrent.{ExecutionContext, Future}
import scala.xml.{Elem, XML}

/**
  * A default implementation of the [[OAuthStorageService]] trait.
  *
  * This implementation stores the data related to an OAuth identity provider
  * in files with the same base name, but different suffixes. Sensitive
  * information can be encrypted if a password is provided.
  */
object OAuthStorageServiceImpl extends OAuthStorageService[OAuthStorageConfig, OAuthConfig, Secret, OAuthTokenData] {
  /** Constant for the suffix used for the file with the OAuth config. */
  val SuffixConfigFile = ".xml"

  /** Constant for the suffix used for the file with the client secret. */
  val SuffixSecretFile = ".sec"

  /** Constant for the suffix of the file with token information. */
  val SuffixTokenFile = ".toc"

  /** Property for the client ID in the persistent config representation. */
  val PropClientId = "client-id"

  /**
    * Property for the authorization endpoint in the persistent config
    * representation.
    */
  val PropAuthorizationEndpoint = "authorization-endpoint"

  /**
    * Property for the token endpoint in the persistent config
    * representation.
    */
  val PropTokenEndpoint = "token-endpoint"

  /** Property for the scope in the persistent config representation. */
  val PropScope = "scope"

  /** Property for the redirect URI in the persistent config representation. */
  val PropRedirectUri = "redirect-uri"

  /** The separator character used within the token file. */
  private val TokenSeparator = "\t"

  /** The object to generate keys. */
  private val keyGenerator = new AESKeyGenerator

  /** The secure random object. */
  private implicit val secRandom: SecureRandom = new SecureRandom

  override def loadConfig(storageConfig: OAuthStorageConfig)
                         (implicit ec: ExecutionContext, system: ActorSystem): Future[OAuthConfig] =
    loadAndMapFile(storageConfig, SuffixConfigFile) { buf =>
      val nodeSeq = XML.loadString(buf.utf8String)
      OAuthConfig(clientID = extractElem(nodeSeq, PropClientId),
        authorizationEndpoint = extractElem(nodeSeq, PropAuthorizationEndpoint),
        tokenEndpoint = extractElem(nodeSeq, PropTokenEndpoint),
        scope = extractElem(nodeSeq, PropScope),
        redirectUri = extractElem(nodeSeq, PropRedirectUri))
    }

  override def loadClientSecret(storageConfig: OAuthStorageConfig)
                               (implicit ec: ExecutionContext, system: ActorSystem): Future[Secret] =
    loadAndMapFile(storageConfig, SuffixSecretFile, Some(storageConfig.encPassword))(buf => Secret(buf.utf8String))

  override def loadTokens(storageConfig: OAuthStorageConfig)
                         (implicit ec: ExecutionContext, system: ActorSystem): Future[OAuthTokenData] =
    loadAndMapFile(storageConfig, SuffixTokenFile, optPwd = Some(storageConfig.encPassword)) { buf =>
      val parts = buf.utf8String.split(TokenSeparator)
      if (parts.length < 2)
        throw new IllegalArgumentException(s"Token file for ${storageConfig.baseName} contains too few tokens.")
      else if (parts.length > 2)
        throw new IllegalArgumentException(s"Token file for ${storageConfig.baseName} has unexpected content.")
      OAuthTokenData(accessToken = parts(0), refreshToken = parts(1))
    }

  /**
    * Extracts the text content of the given child from an XML document. If the
    * child cannot be resolved, an ''IllegalArgumentException'' is thrown; this
    * indicates an invalid configuration file.
    *
    * @param elem  the parent XML element
    * @param child the name of the desired child
    * @return the text content of this child element
    * @throws IllegalArgumentException if the child cannot be found
    */
  private def extractElem(elem: Elem, child: String): String = {
    val text = (elem \ child).text.trim
    if (text.isEmpty)
      throw new IllegalArgumentException(s"Missing mandatory property '$child' in OAuth configuration.")
    text
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
    * @param system           the actor system to materialize streams
    * @tparam T the type of the result
    * @return the result generated by the mapping function
    */
  private def loadAndMapFile[T](storageConfig: OAuthStorageConfig, suffix: String, optPwd: Option[Secret] = None)
                               (f: ByteString => T)
                               (implicit ec: ExecutionContext, system: ActorSystem): Future[T] = {
    val file = fileSource(storageConfig, suffix)
    val source = optPwd.map(pwd => cryptSource(file, pwd)) getOrElse file
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    source.runWith(sink).map(f)
  }

  /**
    * Returns a source that decrypts the passed in source. This is used to
    * load files with sensitive information.
    *
    * @param source the original source
    * @param secret the password for decryption
    * @return the decorated source
    */
  private def cryptSource[Mat](source: Source[ByteString, Mat], secret: Secret): Source[ByteString, Mat] =
    CryptService.decryptSource(keyGenerator.generateKey(secret.secret), source)
}
