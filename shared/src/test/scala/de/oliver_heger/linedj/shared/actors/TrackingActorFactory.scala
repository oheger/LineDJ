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

package de.oliver_heger.linedj.shared.actors

import org.apache.pekko.actor as classic
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Props}
import org.apache.pekko.testkit.TestProbe

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.concurrent.duration.{DurationInt, FiniteDuration}

object TrackingActorFactory:
  /**
    * A default duration to wait when testing that an actor is not terminated.
    */
  private val NonTerminatedDuration = 100.millis

  /**
    * Expects that the given classic actor terminates within a grace period.
    *
    * @param actor  the actor
    * @param system the actor system
    */
  def expectClassicActorTerminated(actor: classic.ActorRef)(using system: classic.ActorSystem): Unit =
    val probe = TestProbe()
    probe.watch(actor)
    probe.expectTerminated(actor)

  /**
    * Tests that the given classic actor does not terminate within a 
    * configurable timeout.
    *
    * @param actor   the actor
    * @param timeout the timeout
    * @param system  the actor system
    */
  def expectClassicActorNotTerminated(actor: classic.ActorRef, timeout: FiniteDuration = NonTerminatedDuration)
                                     (using system: classic.ActorSystem): Unit =
    val probe = TestProbe()
    probe.watch(actor)
    probe.expectNoMessage(timeout)

  /**
    * Expects that the given typed actor terminates within a grace period.
    *
    * @param actor   the actor
    * @param testKit the actor test kit
    * @tparam C the type of the actor reference
    */
  def expectTypedActorTerminated[C](actor: ActorRef[C], testKit: ActorTestKit): Unit =
    val probeWatch = testKit.createDeadLetterProbe()
    probeWatch.expectTerminated(actor)

  /**
    * Expects that the given typed actor does not terminate within a 
    * configurable timeout.
    *
    * @param actor   the actor
    * @param testKit the actor test kit
    * @param timeout the timeout
    * @tparam C the type of the actor reference
    */
  def expectTypedActorNotTerminated[C](actor: ActorRef[C],
                                       testKit: ActorTestKit,
                                       timeout: FiniteDuration = NonTerminatedDuration): Unit =
    val probeWatch = testKit.createTestProbe[WatchedActorTerminated.type]()
    val watchActor = testKit.spawn(watchingBehavior(actor, probeWatch.ref))
    probeWatch.expectNoMessage(timeout)

  /**
    * An internally used message to indicate that an actor that was watched has
    * terminated. This is used to verify that a typed actor did not terminate.
    * Since typed test probes do not support watching actors directly, this
    * functionality has to be implemented manually.
    */
  private case object WatchedActorTerminated

  /**
    * A simple actor implementation that watches another actor and forwards a 
    * notification about the other actor's termination to a third actor. This 
    * is an implementation of the missing functionality to watch an actor from
    * a typed test probe.
    *
    * @param actorToWatch  the actor to watch
    * @param actorToNotify the actor to notify in case of termination
    * @tparam C the type of the actor to watch
    * @return the behavior of the watching actor
    */
  private def watchingBehavior[C](actorToWatch: ActorRef[C], actorToNotify: ActorRef[WatchedActorTerminated.type]):
  Behavior[WatchedActorTerminated.type] =
    Behaviors.setup: context =>
      context.watchWith(actorToWatch, WatchedActorTerminated)

      def handleCommand(): Behavior[WatchedActorTerminated.type] =
        Behaviors.receiveMessage:
          case WatchedActorTerminated =>
            actorToNotify ! WatchedActorTerminated
            Behaviors.stopped

      handleCommand()

  /**
    * Updates a map in an atomic reference in a thread-safe way. This is used 
    * to update the maps with actor references correctly, even if actors are
    * created in different threads.
    *
    * @param ref   the atomic reference storing a map with actors
    * @param key   the key of the new actor
    * @param value the actor reference
    * @tparam V the type of the actor reference
    */
  @tailrec private def updateActorsMap[V](ref: AtomicReference[Map[String, V]], key: String, value: V): Unit =
    val oldMap = ref.get()
    val newMap = oldMap + (key -> value)
    if !ref.compareAndSet(oldMap, newMap) then
      updateActorsMap(ref, key, value)

  /**
    * Helper function to obtain a value from a map which is expected to be 
    * contained in the map.
    *
    * @param key the desired key
    * @param map the map
    * @tparam V the value type of the map
    * @return the fetched value from the map
    */
  private def getExistingKey[V](key: String, map: Map[String, V]): V =
    map.getOrElse(key, unknownKey(key))

  /**
    * Throws an exception because a key in a map could not be resolved.
    *
    * @param key the name of the key
    * @tparam V the value type of the map
    * @return nothing
    */
  private def unknownKey[V](key: String): V =
    throw new NoSuchElementException(s"Could not resolve key '$key'.")
end TrackingActorFactory

/**
  * A special implementation of [[ActorFactory]] that keeps track on the actors
  * that have been created via the factory and provides some functionality for
  * testing their life-cycle. The class can be used in tests to access internal
  * actors created by code under test and to verify that they are properly
  * terminated.
  *
  * @param wrappedFactory the underlying factory for creating actors
  */
class TrackingActorFactory(val wrappedFactory: ActorFactory) extends ActorFactory:

  import TrackingActorFactory.*

  /**
    * A reference holding a map with the classic actors that have been created
    * through this factory keyed by their names.
    */
  private val refClassicActors = new AtomicReference[Map[String, classic.ActorRef]](Map.empty)

  /**
    * A reference holding a map with the typed actors that have been created
    * through this factory keyed by their names.
    */
  private val refTypedActors = new AtomicReference[Map[String, ActorRef[?]]](Map.empty)

  /**
    * Returns a map with the classic actors that have been created through this
    * factory, using the actor names as keys.
    *
    * @return the map with the known classic actors
    */
  def classicActors: Map[String, classic.ActorRef] = refClassicActors.get()

  /**
    * Returns a map with the typed actors that have been created through this
    * factory, using the actor names as keys.
    *
    * @return the map with the known typed actors
    */
  def typedActors: Map[String, ActorRef[?]] = refTypedActors.get()

  override def actorSystem: classic.ActorSystem = wrappedFactory.actorSystem

  override def createClassicActor(props: classic.Props, name: String, optStopCommand: Option[Any]): classic.ActorRef =
    val actor = wrappedFactory.createClassicActor(props, name, optStopCommand)
    updateActorsMap(refClassicActors, name, actor)
    actor

  override def createTypedActor[T](behavior: Behavior[T],
                                   name: String,
                                   props: Props,
                                   optStopCommand: Option[T]): ActorRef[T] =
    val actor = wrappedFactory.createTypedActor(behavior, name, props, optStopCommand)
    updateActorsMap(refTypedActors, name, actor)
    actor

  /**
    * Expects that the classic actor with the given name created by this 
    * factory terminates within a grace period.
    *
    * @param name the name of the actor
    */
  def expectClassicActorTerminated(name: String): Unit =
    val actor = getExistingKey(name, classicActors)
    TrackingActorFactory.expectClassicActorTerminated(actor)(using actorSystem)

  /**
    * Expects that the classic actor with the given name created by this
    * factory does not terminate within a configurable timeout.
    *
    * @param name    the name of the actor
    * @param timeout the timeout
    */
  def expectClassicActorNotTerminated(name: String, timeout: FiniteDuration = NonTerminatedDuration): Unit =
    val actor = getExistingKey(name, classicActors)
    TrackingActorFactory.expectClassicActorNotTerminated(actor, timeout)(using actorSystem)

  /**
    * Expects that the typed actor with the given name created by this factory
    * terminates within a grace period.
    *
    * @param name    the name of the actor
    * @param testKit the actor test kit
    */
  def expectTypedActorTerminated(name: String, testKit: ActorTestKit): Unit =
    val actor = getExistingKey(name, typedActors)
    TrackingActorFactory.expectTypedActorTerminated(actor, testKit)

  /**
    * Expects that the typed actor with the given name created by this factory
    * does not terminate within a configurable timeout.
    *
    * @param name    the name of the actor
    * @param testKit the actor test kit
    * @param timeout the timeout
    */
  def expectTypedActorNotTerminated(name: String,
                                    testKit: ActorTestKit,
                                    timeout: FiniteDuration = NonTerminatedDuration): Unit =
    val actor = getExistingKey(name, typedActors)
    TrackingActorFactory.expectTypedActorNotTerminated(actor, testKit, timeout)
    