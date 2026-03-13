/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.archive.cloud.auth

import com.github.cloudfiles.core.http.Secret
import de.oliver_heger.linedj.shared.actors.ActorFactory
import de.oliver_heger.linedj.shared.actors.ActorFactory.given
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

/**
  * A module providing functionality to deal with credentials.
  *
  * To connect to a cloud archive, credentials are required in any form. How
  * these credentials are managed or obtained depends on the application that
  * interacts with archives. This module defines an abstraction for accessing
  * such credentials and provides an internal implementation based on an actor
  * for managing credentials in a thread-safe way that connects
  * application-specific code to obtain credentials to clients that need these
  * credentials.
  */
object Credentials:
  /** The default name for the credentials manager actor. */
  final val CredentialsManagerName = "credentialsManager"

  /**
    * A default timeout value for querying credentials from a credentials
    * manager actor. In the default use cases, it is expected that credentials
    * are eventually entered by a user. Therefore, this timeout value is set
    * very high. If needed, a custom timeout can be provided.
    */
  given queryCredentialTimeout: Timeout = Timeout(30.days)

  /**
    * An alias for a function that can resolve credentials (for cloud archives)
    * based on keys. It is used by different components to obtain the concrete
    * secrets to connect to a cloud archive. The function expects a string key
    * and returns a [[Future]] with the resolved [[Secret]].
    */
  type ResolverFunc = String => Future[Secret]

  /**
    * A data class representing the information to be stored for a specific
    * credential. This is basically a pair consisting of a (unique) key and the
    * secret value of the credential.
    *
    * @param key   the key of the credential
    * @param value the secret value
    */
  private case class CredentialData(key: String, value: Secret)

  /**
    * An enumeration defining the commands supported by the internal
    * credentials manager actor.
    */
  private enum CredentialsManagerCommand:
    /**
      * A command to query information about a specific credential. The
      * credentials manager actor sends an answer immediately if the credential
      * is known. Otherwise, it records this request and waits until the
      * credential becomes available. So, a result is eventually sent, but
      * maybe with a delay.
      *
      * @param key     the key of the desired credential
      * @param replyTo the actor to send the reply to
      */
    case QueryCredential(key: String,
                         replyTo: ActorRef[CredentialData])

    /**
      * A command to set a specific credential. The actor stores this value. If
      * there are already clients waiting for the value of this credential,
      * they are now notified. The actor also sends a response of type
      * ''Boolean'' that is '''true''' if there is at least one client waiting
      * for this credential and '''false''' otherwise.
      */
    case SetCredential(data: CredentialData,
                       replyTo: ActorRef[Boolean])

    /**
      * A command to clear a specific credential. If a value is assigned to
      * this key, it is removed. Otherwise, this command has no effect.
      */
    case ClearCredential(key: String)

    /**
      * A command to request information about the currently known credential
      * keys.
      */
    case GetKeys(replyTo: ActorRef[Set[CredentialKeyInfo]])

    /**
      * A command that tells the credentials manager actor to stop itself.
      */
    case Stop
  end CredentialsManagerCommand

  /**
    * A data class to store information about a credential key. Instances of
    * this class are returned by [[CredentialSetter]] when querying the current
    * set of keys. Credential management distinguishes between two cases: A key
    * with a values has already been set, and a key is requested by at least
    * one consumer, but the value is not yet available. An instance of this
    * class has a flag that allows to determine in which state the associated
    * key is.
    *
    * @param key     the name of the key
    * @param pending '''true''' if there are consumers waiting for this
    *                credential; '''false''' if the value of the credential has
    *                already been set
    */
  final case class CredentialKeyInfo(key: String,
                                     pending: Boolean)

  /**
    * A trait that allows setting the values of credentials when they become
    * available. Via this abstraction, credential values are passed to the
    * credential management. If there are clients waiting for these
    * credentials, they are notified. Otherwise, the credentials are only
    * stored, so that they are available once a client asks for them.
    */
  trait CredentialSetter:
    /**
      * Sets the value of a credential. The return value indicates whether a
      * client was waiting for this credential.
      *
      * @param key   the key of the credential
      * @param value the secret value
      * @return a [[Future]] with a flag whether a client has already asked for
      *         this credential
      */
    def setCredential(key: String, value: Secret): Future[Boolean]

    /**
      * Removes the credential with the given key from this object. Consumers
      * requesting this key will receive a [[Future]] that only completes when
      * the value is set anew. This function can be used for instance, if it
      * turns out that the value was incorrect.
      *
      * @param key the key of the credential to remove
      */
    def clearCredential(key: String): Unit

    /**
      * Returns a [[Set]] with [[CredentialKeyInfo]] objects describing the
      * keys that are currently known to this object. This information is
      * obtained asynchronously; therefore, result is a [[Future]].
      *
      * @return a [[Future]] with information about known credential keys
      */
    def credentialKeys: Future[Set[CredentialKeyInfo]]

  /**
    * A helper class to manage the state of known credentials.
    *
    * This class holds the information required by the credential manager 
    * actor. It provides methods to update the state according to the supported
    * use cases.
    *
    * @param credentials a map storing the known credentials
    * @param clients     a map with the clients waiting for a specific key
    */
  private class CredentialsState(val credentials: Map[String, CredentialData],
                                 val clients: Map[String, List[ActorRef[CredentialData]]]):
    /**
      * Updates this state for a query credential operation. If the requested
      * credential is known, the request can be answered, and the state does 
      * not need to be changed. Otherwise, the client has to be stored, so that
      * it can be notified when the credential becomes available. The function
      * returns a tuple with the updated state and the optional credential, so
      * that the caller can answer the request if possible.
      *
      * @param key    the key of the desired credential
      * @param client the requesting actor
      * @return a tuple with the updated state and the credential if known
      */
    def queryCredential(key: String, client: ActorRef[CredentialData]): (CredentialsState, Option[CredentialData]) =
      val optData = credentials.get(key)
      val nextState = optData match
        case Some(_) => this
        case None =>
          val waitingClients = clients.getOrElse(key, Nil)
          CredentialsState(credentials, clients + (key -> (client :: waitingClients)))
      (nextState, optData)

    /**
      * Updates this state for a credential becoming available. The credential 
      * is stored. If there are clients waiting for the affected key, they can
      * now be notified. The function returns a tuple with the updated state 
      * and a collection of actors that are waiting for this credential.
      *
      * @param data the data about the incoming credential
      * @return a tuple with the updated state and the actors to notify
      */
    def addCredential(data: CredentialData): (CredentialsState, Iterable[ActorRef[CredentialData]]) =
      (CredentialsState(credentials + (data.key -> data), clients - data.key), clients.getOrElse(data.key, Nil))

    /**
      * Updates this state if a credential key is removed. This key is just
      * removed from the internal map if it was present. Otherwise, no change
      * is required.
      *
      * @param key the key to be removed
      * @return the updated state
      */
    def clearCredential(key: String): CredentialsState =
      if credentials.contains(key) then
        CredentialsState(credentials - key, clients)
      else
        this
  end CredentialsState

  /** Constant for an initial, empty credentials state instance. */
  private val InitialCredentialsState = CredentialsState(Map.empty, Map.empty)

  /**
    * Creates a credentials manager actor and a [[ResolverFunc]] that queries
    * this actor. The resulting tuple can be used to implement an 
    * application-specific credentials management: Credentials obtained via 
    * whatever mechanism can be passed to the actor. Clients query the required
    * credentials then from the [[ResolverFunc]] which internally delegates to
    * the actor. 
    *
    * @param factory      the factory to create the actor
    * @param actorName    the name for the actor
    * @param queryTimeout a timeout applied by the resolver function when 
    *                     querying the actor
    * @return a tuple with the actor reference and the resolver function
    */
  def setUpCredentialsManager(factory: ActorFactory, actorName: String = CredentialsManagerName)
                             (using queryTimeout: Timeout): (CredentialSetter, ResolverFunc) =
    val actor = factory.createTypedActor(
      handleCredentialsCommand(InitialCredentialsState),
      actorName,
      optStopCommand = Some(CredentialsManagerCommand.Stop)
    )
    (createCredentialSetter(actor, factory), createResolverFunc(actor, factory))

  /**
    * The command handler function of the credentials manager actor.
    *
    * @param state the current state of the actor
    * @return the updated behavior
    */
  private def handleCredentialsCommand(state: CredentialsState): Behavior[CredentialsManagerCommand] =
    Behaviors.receiveMessage:
      case CredentialsManagerCommand.Stop =>
        Behaviors.stopped
      case CredentialsManagerCommand.QueryCredential(key, replyTo) =>
        val (nextState, optData) = state.queryCredential(key, replyTo)
        optData.foreach(replyTo.!)
        handleCredentialsCommand(nextState)
      case CredentialsManagerCommand.SetCredential(data, replyTo) =>
        val (nextState, clients) = state.addCredential(data)
        clients.foreach(c => c ! data)
        replyTo ! clients.nonEmpty
        handleCredentialsCommand(nextState)
      case CredentialsManagerCommand.ClearCredential(key) =>
        handleCredentialsCommand(state.clearCredential(key))
      case CredentialsManagerCommand.GetKeys(replyTo) =>
        val keyInfo = (state.credentials.keys.map(key => CredentialKeyInfo(key, pending = false)) ++
          state.clients.keys.map(key => CredentialKeyInfo(key, pending = true))).toSet
        replyTo ! keyInfo
        Behaviors.same

  /**
    * Returns a [[ResolverFunc]] that queries credentials from the given 
    * credentials manager actor.
    *
    * @param actor   the actor
    * @param factory the actor factory
    * @param timeout the timeout for queries
    * @return the resolver function
    */
  private def createResolverFunc(actor: ActorRef[CredentialsManagerCommand], factory: ActorFactory)
                                (using timeout: Timeout): ResolverFunc =
    given classic.ActorSystem = factory.actorSystem

    key =>
      askCredentialActor[CredentialData](actor, factory): ref =>
        CredentialsManagerCommand.QueryCredential(key, ref)
      .map(_.value)

  /**
    * Returns a [[CredentialSetter]] object that passes credentials to the
    * given credentials manager actor.
    *
    * @param actor   the actor
    * @param factory the actor factory
    * @param timeout the timeout for ''ask'' operations
    * @return the [[CredentialSetter]] object
    */
  private def createCredentialSetter(actor: ActorRef[CredentialsManagerCommand], factory: ActorFactory)
                                    (using timeout: Timeout): CredentialSetter =
    new CredentialSetter:
      override def setCredential(key: String, value: Secret): Future[Boolean] =
        val data = CredentialData(key, value)
        askCredentialActor[Boolean](actor, factory): ref =>
          CredentialsManagerCommand.SetCredential(data, ref)

      override def clearCredential(key: String): Unit =
        actor ! CredentialsManagerCommand.ClearCredential(key)

      override def credentialKeys: Future[Set[CredentialKeyInfo]] =
        askCredentialActor[Set[CredentialKeyInfo]](actor, factory): ref =>
          CredentialsManagerCommand.GetKeys(ref)

  /**
    * Helper function to apply the ''ask'' pattern to the given credential
    * actor reference.
    *
    * @param actor   the actor to ask
    * @param factory the actor factory
    * @param replyTo the function to construct the query message
    * @param timeout the timeout for the operation
    * @tparam Res the type of the result
    * @return a [[Future]] with the response from the actor
    */
  private def askCredentialActor[Res](actor: ActorRef[CredentialsManagerCommand], factory: ActorFactory)
                                     (replyTo: ActorRef[Res] => CredentialsManagerCommand)
                                     (using timeout: Timeout): Future[Res] =
    given ActorSystem[_] = factory.actorSystem.toTyped

    actor.ask(replyTo)
