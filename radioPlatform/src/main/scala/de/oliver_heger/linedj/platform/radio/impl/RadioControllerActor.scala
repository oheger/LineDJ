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

package de.oliver_heger.linedj.platform.radio.impl

import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.comm.ServiceDependencies.{RegisterService, ServiceDependency, UnregisterService}
import de.oliver_heger.linedj.server.discovery.ServerDiscovery
import de.oliver_heger.linedj.shared.actors.ActorFactory
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.Timeout

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

/**
  * A module providing an actor implementation that controls the interaction
  * with a player server to enable radio playback to clients of the radio
  * platform project.
  *
  * On startup, an instance performs a discovery operation to find the URL of
  * the player server. When this is successful, it creates the services and
  * actors required to send commands to the server and receive notifications
  * about state updates. When it gets closed, it releases all these resources
  * again.
  *
  * This actor implementation is designed to be safely used by the OSGi
  * component setting up the radio platform.
  */
object RadioControllerActor:
  /** The name of the controller actor instance. */
  private val ControllerActorName = "radioControllerActor"

  /** The name of the managed radio player actor. */
  private val PlayerActorName = "radioPlayerActor"

  /** The name of the discovery operation. */
  private val DiscoveryName = "radioPlayerControllerDiscovery"

  /**
    * A [[ServiceDependency]] to represent the radio player controller. The
    * actor registers this dependency when the connection to the player server
    * could be established, and the system is ready to process commands for the
    * radio player.
    */
  final val RadioPlayerControllerDependency = ServiceDependency("lineDJ.radioPlayerController")

  /**
    * An enumeration defining the different commands handled by this actor
    * implementation.
    */
  enum Command:
    /**
      * Command to stop an actor instance. This also releases all resources
      * used by this instance.
      */
    case Stop

  /**
    * An internal command with the result of the discovery operation.
    *
    * @param result the result of the discovery operation
    */
  private final case class DiscoveryResult(result: Try[String])

  /** Alias for all commands handled internally by this actor. */
  private type InternalCommand = Command | DiscoveryResult

  /**
    * A data class holding the state of a controller actor instance.
    *
    * @param optService     the service to interact with the player server
    * @param optPlayerActor the actor managing the radio player
    */
  private final case class ControllerState(optService: Option[PlayerServiceImpl],
                                           optPlayerActor: Option[ActorRef[RadioPlayerActor.Command]])

  /**
    * A factory trait to create new instances of this actor implementation.
    */
  trait Factory:
    /**
      * Returns a new actor instance based on the provided parameters.
      *
      * @param discoveryParams  the parameters for the discovery operation
      * @param messageBus       the system message bus
      * @param discoveryFactory the factory for a discover operation
      * @param serviceFactory   the factory for the [[PlayerService]]
      * @param playerFactory    the factory for the player actor
      * @param actorFactory     the factory to create new actor instances
      * @param timeout          the timeout for service interactions
      * @return the reference to the new actor instance
      */
    def apply(discoveryParams: ServerDiscovery.DiscoveryParams,
              messageBus: MessageBus,
              discoveryFactory: ServerDiscovery.Factory = ServerDiscovery.discover,
              serviceFactory: PlayerServiceImpl.Factory = PlayerServiceImpl.newInstance,
              playerFactory: RadioPlayerActor.Factory = RadioPlayerActor.newInstance)
             (using actorFactory: ActorFactory, timeout: Timeout): ActorRef[Command]

  /**
    * A default factory instance for creating new controller actors.
    */
  final val newInstance: Factory = new Factory:
    override def apply(discoveryParams: ServerDiscovery.DiscoveryParams,
                       messageBus: MessageBus,
                       discoveryFactory: ServerDiscovery.Factory,
                       serviceFactory: PlayerServiceImpl.Factory,
                       playerFactory: RadioPlayerActor.Factory)
                      (using actorFactory: ActorFactory, timeout: Timeout): ActorRef[Command] =
      actorFactory.createTypedActor(
        setUpActor(discoveryParams, messageBus, discoveryFactory, serviceFactory, playerFactory)
          (using actorFactory, timeout).narrow[Command],
        ControllerActorName
      )

  /**
    * Creates the behavior of a new controller actor instance.
    *
    * @param discoveryParams  the parameters for the discovery operation
    * @param messageBus       the system message bus
    * @param discoveryFactory the factory for a discover operation
    * @param serviceFactory   the factory for the player service
    * @param playerFactory    the factory for the player actor
    * @param actorFactory     the factory to create new actor instances
    * @param timeout          the timeout for service interactions
    * @return the behavior of the new actor instance
    */
  private def setUpActor(discoveryParams: ServerDiscovery.DiscoveryParams,
                         messageBus: MessageBus,
                         discoveryFactory: ServerDiscovery.Factory,
                         serviceFactory: PlayerServiceImpl.Factory,
                         playerFactory: RadioPlayerActor.Factory)
                        (using actorFactory: ActorFactory, timeout: Timeout): Behavior[InternalCommand] =
    Behaviors.setup: context =>
      given ExecutionContext = context.executionContext

      given classic.ActorSystem = context.system.classicSystem

      val discoveryHandle = discoveryFactory(discoveryParams, DiscoveryName)(using actorFactory)
      discoveryHandle.futResult.onComplete: result =>
        context.self ! DiscoveryResult(result)

      /**
        * Handles the result of the discovery operation. On success, the
        * resources for interacting with the player server are created.
        *
        * @param result the result of the discovery operation
        * @param state  the current actor state
        * @return the behavior for processing further commands
        */
      def handleDiscoveryResult(result: Try[String],
                                state: ControllerState): Behavior[InternalCommand] =
        result match
          case Success(serverUri) =>
            context.log.info("Discovered player server at {}. Creating RadioPlayerActor.", serverUri)
            val service = serviceFactory(serverUri)
            val playerActor = actorFactory.createTypedActor(
              playerFactory(serverUri, service, messageBus),
              PlayerActorName
            )
            messageBus.publish(RegisterService(RadioPlayerControllerDependency))
            handleCommands(ControllerState(optService = Some(service), optPlayerActor = Some(playerActor)))

          case Failure(exception) =>
            context.log.error("Could not discover the radio player server.", exception)
            handleCommands(state)

      /**
        * Returns the behavior for handling commands based on the current state.
        *
        * @param state the current actor state
        * @return the behavior for processing commands
        */
      def handleCommands(state: ControllerState): Behavior[InternalCommand] =
        Behaviors.receiveMessage:
          case DiscoveryResult(result) =>
            handleDiscoveryResult(result, state)

          case Command.Stop =>
            discoveryHandle.close()
            state.optPlayerActor.foreach(_ ! RadioPlayerActor.Command.Stop)
            state.optService.foreach(_.close())
            if state.optService.isDefined then
              messageBus.publish(UnregisterService(RadioPlayerControllerDependency))
            Behaviors.stopped

      handleCommands(ControllerState(optService = None, optPlayerActor = None))
