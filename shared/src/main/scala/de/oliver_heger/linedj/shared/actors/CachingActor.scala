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

import de.oliver_heger.linedj.utils.LRUCache
import org.apache.pekko.actor as classics
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Scheduler}
import org.apache.pekko.stream.BoundedSourceQueue
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.apache.pekko.util.Timeout

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
  * An actor implementation that provides a generic caching functionality.
  *
  * A concrete instance can manage values of a specific type that are
  * associated with keys. The actor provides a simple protocol to query keys.
  * If a key has not yet been encountered, the actor uses a configurable
  * function to retrieve its value asynchronously. If this is successful, the
  * value is stored, so that it is directly available for later queries.
  *
  * It is possible to limit the parallelism when fetching data via the resolver
  * function. In this mode, the actor makes sure that only the configured
  * number of calls of the function can happen in parallel, even when the actor
  * gets queried from multiple clients.
  *
  * The data in the cache is hold in a dedicated storage abstraction which just
  * provides simple get and put functionality. Here custom implementations
  * can be passed in to support different caching logic (for instance LRU
  * caching or entries that are valid only for a limited time period). Some
  * default implementations for cache stores are already provided by this
  * object.
  */
object CachingActor:
  /**
    * The size of the buffer to be used for the request queue of the actor
    * enforcing limited parallelism. This is a big value to make sure that for
    * typical use cases, no capacity limit is reached. Note that the limit is
    * for all requests, which are typically multi-key requests (and such a 
    * request counts as 1).
    */
  private val QueueBufferSize = 500

  /**
    * Definition of a function to resolve (fetch or compute) a value for a
    * given key. When the caching actor is asked for a key which it cannot
    * find in its storage, it invokes this function to obtain the corresponding
    * value. This value is then stored in the cache for the affected key.
    *
    * @tparam K the type of the keys
    * @tparam V the type of the values
    */
  type KeyResolverFunc[K, V] = K => Future[V]

  /**
    * A trait defining functionality for storing values in the cache. The
    * caching actor is initialized with an implementation that it uses to hold
    * the value for the already encountered keys. The interface is similar to
    * what a plain map provides. A concrete implementation is expected to be
    * mutable, since the state is updated in place. By changing the mutable
    * state in an actor, no threading issues and race conditions can occur.
    *
    * @tparam K the type of the keys
    * @tparam V the type of the values
    */
  trait Store[K, V]:
    /**
      * Returns an [[Option]] with the current value of a key. A result of
      * ''None'' obviously means that the passed in key is not available in the
      * store.
      *
      * @param key the key in question
      * @return an [[Option]] with the value of the key
      */
    def get(key: K): Option[V]

    /**
      * Stores a key-value pair. This function is called when the caching
      * actor has obtained a new value.
      *
      * @param key   the key of the new value
      * @param value the new value to be stored
      */
    def put(key: K, value: V): Unit
  end Store

  /**
    * A factory trait to create behaviors of new actor instances.
    */
  trait Factory:
    /**
      * Returns the [[Behavior]] of a new instance of the caching actor which is
      * configured with the given parameters.
      *
      * @param resolver      the function to resolve unknown keys
      * @param store         the object to store the data of the cache
      * @param parallelLimit an optional limit for parallelism
      * @tparam K the type of the keys
      * @tparam V the type of the values
      * @return the [[Behavior]] of the new instance
      */
    def apply[K, V](resolver: KeyResolverFunc[K, V],
                    store: Store[K, V] = mapStore[K, V],
                    parallelLimit: Option[Int] = None): Behavior[CacheCommand[K, V]]
  end Factory

  /**
    * A data class representing the response to a query for a key from the
    * cache. The operation can fail if the value to the key needs to be
    * obtained and the resolver function returns a failed future. Therefore, a
    * [[Try]] with the value is contained.
    *
    * @param key        the key of the requested key
    * @param triedValue a [[Try]] with the value for this key
    * @tparam K the type of the key
    * @tparam V the type of the value
    */
  final case class CacheResponse[K, V](key: K,
                                       triedValue: Try[V])

  /**
    * A data class representing the response to a query for multiple keys from
    * the cache. An instant distinguishes between the keys that could be 
    * resolved successfully and those for which the resolver function threw an
    * exception.
    *
    * @param resolved a map with resolved keys and their values
    * @param failures a map with failed keys and the exceptions thrown
    * @tparam K the type of keys
    * @tparam V the type of values
    */
  final case class MultiCacheResponse[K, V](resolved: Map[K, V],
                                            failures: Map[K, Throwable])

  /**
    * An enumeration defining the commands supported by this actor
    * implementation.
    *
    * @tparam K the type of the keys
    * @tparam V the type of the values
    */
  enum CacheCommand[K, V]:
    /**
      * A command to query the value of a specific key.
      *
      * @param key     the key in question
      * @param replyTo the actor to send the response to
      */
    case Get(key: K,
             replyTo: ActorRef[CacheResponse[K, V]])

    /**
      * A command to query multiple keys at once. If the values of multiple
      * keys are needed, using this command instead of sending many [[Get]]
      * requests is typically more convenient and efficient.
      *
      * @param keys    the keys to be queried
      * @param replyTo the actor to send the response to
      */
    case GetMultiple(keys: Iterable[K],
                     replyTo: ActorRef[MultiCacheResponse[K, V]])

    /**
      * A command to stop this actor instance.
      */
    case Stop()
  end CacheCommand

  /**
    * A default [[Factory]] for creating new actor instances.
    */
  final val newInstance: Factory = new Factory:
    override def apply[K, V](resolver: KeyResolverFunc[K, V],
                             store: Store[K, V],
                             parallelLimit: Option[Int]): Behavior[CacheCommand[K, V]] =
      parallelLimit.fold(handleCacheCommand(resolver, store, Map.empty).narrow): limit =>
        limitedParallelismBehavior(resolver, store, limit)

  /**
    * Returns a new [[Store]] implementation based on a [[Map]]. This store 
    * holds all values that are retrieved by the cache. It can therefore grow
    * infinitely.
    *
    * @tparam K the type of the keys
    * @tparam V the type of the values
    * @return the new map-based [[Store]] object
    */
  def mapStore[K, V]: Store[K, V] = new MapStore

  /**
    * Returns a new [[Store]] implementation based on a [[LRUCache]] object.
    * Using this store, the size of the cache can be restricted. It keeps 
    * entries that have been accessed recently, while older ones are discarded
    * when the maximum capacity is reached.
    *
    * @param capacity the number of entries to store in the cache
    * @tparam K the type of the keys
    * @tparam V the type of the values
    * @return the new [[Store]] object with LRU semantics
    */
  def lruStore[K, V](capacity: Int): Store[K, V] = new LRUStore(capacity)

  extension [K, V](actor: ActorRef[CacheCommand[K, V]])
    /**
      * Queries the cache managed by this actor for the given key and returns a 
      * [[Future]] with the result. If the resolver function failed to obtain 
      * the value for this key, the returned [[Future]] fails with the 
      * exception returned by this function.
      *
      * @param key       the key in question
      * @param scheduler the scheduler for this operation
      * @param timeout   the timeout for this operation
      * @return a [[Future]] with the value of this key
      */
    def get(key: K)(using scheduler: Scheduler, ec: ExecutionContext, timeout: Timeout): Future[V] =
      actor.ask[CacheResponse[K, V]](ref => CacheCommand.Get(key, ref)).flatMap: response =>
        Future.fromTry(response.triedValue)

    /**
      * Queries the cache managed by this actor for multiple keys at once and 
      * returns a [[Future]] with the result. Unless there is a timeout, the
      * resulting [[Future]] does not fail. The result object allows to 
      * distinguish between keys that could be resolved successfully and those 
      * that failed.
      *
      * @param keys      the keys to query
      * @param scheduler the scheduler for this operation
      * @param timeout   the timeout for this operation
      * @return a [[Future]] with a result object for the queried keys
      */
    def getMultiple(keys: Iterable[K])(using scheduler: Scheduler, timeout: Timeout):
    Future[MultiCacheResponse[K, V]] =
      actor.ask[MultiCacheResponse[K, V]](ref => CacheCommand.GetMultiple(keys, ref))

  /**
    * A simple [[Store]] implementation based on a map.
    */
  private class MapStore[K, V] extends Store[K, V]:
    /** The internal map holding the data. */
    private var map = Map.empty[K, V]

    override def get(key: K): Option[V] = map.get(key)

    override def put(key: K, value: V): Unit =
      map = map + (key -> value)
  end MapStore

  /**
    * A [[Store]] implementation that uses a [[LRUCache]] to hold its data.
    *
    * @param capacity the number of entries the cache can store
    * @tparam K the type of the keys
    * @tparam V the type of the values
    */
  private class LRUStore[K, V](capacity: Int) extends Store[K, V]:
    /** The internal cache instance that holds the data. */
    private val cache = new LRUCache[K, V](capacity)()

    override def get(key: K): Option[V] = cache.get(key)

    override def put(key: K, value: V): Unit = cache.addItem(key, value)
  end LRUStore

  /**
    * Type alias for the commands that are handled internally by the actor
    * implementation. This contains internal commands required by the caching
    * logic.
    *
    * @tparam K the type of the keys
    * @tparam V the type of the values
    */
  private type CacheManagementCommand[K, V] = CacheCommand[K, V] | CacheResponse[K, V]

  /**
    * The command handler function for the caching actor.
    *
    * @param resolver   the resolver function
    * @param store      the store
    * @param inProgress a map for keys that are currently processed by the
    *                   resolver function
    * @tparam K the type of the keys
    * @tparam V the type of the values
    * @return the updated behavior
    */
  private def handleCacheCommand[K, V](resolver: KeyResolverFunc[K, V],
                                       store: Store[K, V],
                                       inProgress: Map[K, List[ActorRef[CacheResponse[K, V]]]]):
  Behavior[CacheManagementCommand[K, V]] =
    Behaviors.receive:
      case (ctx, CacheCommand.Get(key, replyTo)) =>
        store.get(key) match
          case Some(value) =>
            replyTo ! CacheResponse(key, Success(value))
            Behaviors.same
          case None =>
            val nextInProgress = inProgress.get(key) match
              case Some(pending) =>
                inProgress + (key -> (replyTo :: pending))
              case None =>
                ctx.log.info("Encountered unknown key '{}'. Resolving it.", key)
                import ctx.executionContext
                resolver(key).onComplete: triedResult =>
                  ctx.self ! CacheResponse(key, triedResult)
                inProgress + (key -> List(replyTo))
            handleCacheCommand(resolver, store, nextInProgress)

      case (ctx, response@CacheResponse(key, triedValue)) =>
        ctx.log.info("Value for key '{}' could be resolved: {}.", key, triedValue.isSuccess)
        inProgress.getOrElse(key, Nil).foreach(_ ! response)
        triedValue.foreach(v => store.put(key, v))
        handleCacheCommand(resolver, store, inProgress - key)

      case (ctx, CacheCommand.GetMultiple(keys, replyTo)) =>
        import ctx.{executionContext, system}
        val (knownKeys, unknownKeys) = keys.foldRight((Map.empty[K, V], Set.empty[K])):
          case (key, t@(known, unknown)) =>
            if known.contains(key) || unknown.contains(key) then t
            else
              store.get(key) match
                case Some(value) => (known + (key -> value), unknown)
                case None => (known, unknown + key)
        val self = ctx.self

        // Use a huge timeout for ask, so that timeouts are handled by the caller.
        given Timeout = Timeout(100.days)

        Future:
          val requests = unknownKeys.map: key =>
            self.ask[CacheResponse[K, V]](ref => CacheCommand.Get(key, ref))
          Future.sequence(requests) foreach : results =>
            val (resolved, failures) = results.foldRight((knownKeys, Map.empty[K, Throwable])):
              case (result, (res, fail)) =>
                result.triedValue match
                  case Success(value) => (res + (result.key -> value), fail)
                  case Failure(exception) => (res, fail + (result.key -> exception))
            replyTo ! MultiCacheResponse(resolved, failures)
        Behaviors.same

      case (_, CacheCommand.Stop()) =>
        Behaviors.stopped

  /**
    * Returns the behavior of an actor that implements caching, but with 
    * limited parallelism when it comes to accessing the resolver function.
    * This actor uses a normal caching actor under the hood, but requests to
    * query keys are run through a stream with enforced limited parallelism.
    *
    * @param resolver      the resolver function
    * @param store         the store storing the cached information
    * @param parallelLimit the limit for parallelism
    * @tparam K the type of the keys
    * @tparam V the type of the values
    * @return the behavior of the actor
    */
  private def limitedParallelismBehavior[K, V](resolver: KeyResolverFunc[K, V],
                                               store: Store[K, V] = mapStore[K, V],
                                               parallelLimit: Int): Behavior[CacheCommand[K, V]] =
    Behaviors.setup: context =>
      val cacheActor = context.spawnAnonymous(newInstance(resolver, store))
      val cacheQueue = limitedParallelismRequestQueue(context, parallelLimit, cacheActor)

      Behaviors.receiveMessage:
        case CacheCommand.Get(key, replyTo) =>
          cacheQueue.offer((replyTo, List(key)))
          Behaviors.same

        case CacheCommand.GetMultiple(keys, replyTo) =>
          val uniqueKeys = keys.toSet
          val multiResponseActor = context.spawnAnonymous(
            handleMultiGetCommand(uniqueKeys.size, replyTo, Map.empty, Map.empty)
          )
          cacheQueue.offer((multiResponseActor, uniqueKeys))
          Behaviors.same

        case CacheCommand.Stop() =>
          cacheQueue.complete()
          Behaviors.stopped

  /**
    * The command handler function of an actor that collects all results of a 
    * multi-get request with limited parallelism. After all responses have been
    * received, the actor sends a result message to the client and stops 
    * itself.
    *
    * @param keyCount the number of queried keys
    * @param replyTo  the actor to send the response to
    * @param resolved the successful results collected so far
    * @param failures the failure results collected so far
    * @tparam K the type of the keys
    * @tparam V the type of the values
    * @return the behavior of the result collector actor
    */
  private def handleMultiGetCommand[K, V](keyCount: Int,
                                          replyTo: ActorRef[MultiCacheResponse[K, V]],
                                          resolved: Map[K, V],
                                          failures: Map[K, Throwable]): Behavior[CacheResponse[K, V]] =
    Behaviors.receiveMessage:
      case CacheResponse(key, triedValue) =>
        val (nextResolved, nextFailures) = triedValue match
          case Success(value) =>
            (resolved + (key -> value), failures)
          case Failure(exception) =>
            (resolved, failures + (key -> exception))
        if nextResolved.size + nextFailures.size == keyCount then
          replyTo ! MultiCacheResponse(nextResolved, nextFailures)
          Behaviors.stopped
        else
          handleMultiGetCommand(keyCount, replyTo, nextResolved, nextFailures)

  /**
    * Returns a queue for sending requests to the underlying cache actor that
    * is backed by a stream enforcing limited parallelism.
    *
    * @param context       the actor context
    * @param parallelLimit the limit for parallelism
    * @param cache         the underlying cache actor
    * @tparam K the type of the keys
    * @tparam V the type of the values
    * @return the queue to query the cache actor
    */
  private def limitedParallelismRequestQueue[K, V](context: ActorContext[CacheCommand[K, V]],
                                                   parallelLimit: Int,
                                                   cache: ActorRef[CacheCommand[K, V]]):
  BoundedSourceQueue[(ActorRef[CacheResponse[K, V]], Iterable[K])] =
    // Use a huge timeout, so that timeouts are handled by callers.
    given Timeout = Timeout(30.days)

    given Scheduler = context.system.scheduler

    given ExecutionContext = context.executionContext

    given classics.ActorSystem = context.system.toClassic

    val sink = Sink.foreach[(ActorRef[CacheResponse[K, V]], CacheResponse[K, V])]:
      case (actor, response) => actor ! response

    Source.queue[(ActorRef[CacheResponse[K, V]], Iterable[K])](QueueBufferSize)
      .mapConcat:
        case (ref, keys) =>
          keys.map(k => (ref, k))
      .mapAsyncUnordered(parallelLimit):
        case (ref, key) =>
          cache.ask[CacheResponse[K, V]](r => CacheCommand.Get(key, r)).map: response =>
            ref -> response
      .toMat(sink)(Keep.left)
      .run()
