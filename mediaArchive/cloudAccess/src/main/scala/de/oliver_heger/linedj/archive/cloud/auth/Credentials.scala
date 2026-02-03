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

package de.oliver_heger.linedj.archive.cloud.auth

import com.github.cloudfiles.core.http.Secret
import de.oliver_heger.linedj.shared.actors.ActorFactory
import org.apache.pekko.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.util.Timeout

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

/**
  * A module providing functionality to deal with credentials.
  *
  * To connect to a cloud archive, credentials are required in any form. How
  * these credentials are managed or obtained depends on the application that
  * interacts with archives. This module defines an abstraction for accessing
  * such credentials and provides an implementation of an actor for managing
  * credentials that connects application-specific code to obtain credentials
  * to clients that need these credentials.
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
  final case class CredentialData(key: String, value: Secret)

  /**
    * A data class representing a command to query information about a specific
    * credential. The credentials manager actor sends an answer immediately if
    * the credential is known. Otherwise, it records this request and waits
    * until the credential becomes available. So, a result is eventually sent,
    * but maybe with a delay.
    *
    * @param key      the key of the desired credential
    * @param replayTo the actor to send the reply to
    */
  final case class QueryCredential(key: String,
                                   replayTo: ActorRef[CredentialData])

  /**
    * A command that tells the credentials manager actor to stop itself.
    */
  case object Stop

  /**
    * Definition of the command type that is handled by the credentials manager
    * actor. This is a union type, which makes it possible to let clients
    * operate on narrowed actor references that support only the subcommands
    * they need.
    */
  type CredentialsManagerCommand = CredentialData | QueryCredential | Stop.type

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
                             (using queryTimeout: Timeout): (ActorRef[CredentialsManagerCommand], ResolverFunc) =
    val actor = factory.createTypedActor(
      handleCredentialsCommand(InitialCredentialsState),
      actorName,
      optStopCommand = Some(Stop)
    )
    (actor, createResolverFunc(actor, factory))

  /**
    * The command handler function of the credentials manager actor.
    *
    * @param state the current state of the actor
    * @return the updated behavior
    */
  private def handleCredentialsCommand(state: CredentialsState): Behavior[CredentialsManagerCommand] =
    Behaviors.receiveMessage:
      case Stop =>
        Behaviors.stopped
      case QueryCredential(key, replayTo) =>
        val (nextState, optData) = state.queryCredential(key, replayTo)
        optData.foreach(replayTo.!)
        handleCredentialsCommand(nextState)
      case data: CredentialData =>
        val (nextState, clients) = state.addCredential(data)
        clients.foreach(c => c ! data)
        handleCredentialsCommand(nextState)

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
    given ActorSystem[_] = factory.actorSystem.toTyped

    given ExecutionContext = factory.actorSystem.dispatcher

    key =>
      actor.ask[CredentialData](ref => QueryCredential(key, ref)).map(_.value)
