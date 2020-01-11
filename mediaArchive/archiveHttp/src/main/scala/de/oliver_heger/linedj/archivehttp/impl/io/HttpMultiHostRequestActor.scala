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

package de.oliver_heger.linedj.archivehttp.impl.io

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.http.scaladsl.model.Uri
import de.oliver_heger.linedj.archivehttp.http.HttpRequests.SendRequest
import de.oliver_heger.linedj.utils.LRUCache


object HttpMultiHostRequestActor {
  /**
    * Returns a ''Props'' object for creating a new instance of this actor.
    *
    * @param cacheSize        the size of the host cache
    * @param requestQueueSize the size of the request queue for child request
    *                         actors
    * @return a ''Props'' for creating a new actor instance
    */
  def apply(cacheSize: Int, requestQueueSize: Int): Props =
    Props(classOf[HttpMultiHostRequestActor], cacheSize, requestQueueSize)
}

/**
  * An actor implementation that can send HTTP requests to multiple hosts.
  *
  * [[HttpRequestActor]] is configured with a single host, to which all
  * requests are sent. This actor creates such request actors dynamically
  * whenever a new host is encountered. This is needed by certain HTTP-based
  * protocols, for instance if download requests are served by a different
  * host than API requests.
  *
  * Request actors created by this actor are hold in a LRU cache with a
  * configurable capacity. If an actor is removed from this cache (because the
  * maximum capacity is reached), it is stopped.
  *
  * @param cacheSize        the size of the host cache
  * @param requestQueueSize the size of the request queue for child request
  *                         actors
  */
class HttpMultiHostRequestActor(cacheSize: Int, requestQueueSize: Int) extends Actor with HttpExtensionActor
  with ActorLogging {
  /**
    * Reference to the HTTP actor for executing requests. Note that this field
    * is not used by this implementation. There is not a single managed request
    * actor, but multiple ones.
    */
  override protected val httpActor: ActorRef = null

  /** A cache for the request actors for specific hosts. */
  private val requestActors = new LRUCache[Uri.Authority, ActorRef](cacheSize)()

  /**
    * This method needs to be overridden here because the super implementation
    * tries to stop the underlying HTTP actor, which is '''null'''.
    */
  override def postStop(): Unit = {}

  override def receive: Receive = {
    case req: SendRequest =>
      val requestActor = fetchRequestActorForUri(req.request.uri)
      requestActor forward req
  }

  /**
    * Returns a ''Props'' object for creating a request actor for the given
    * URI.
    *
    * @param uri the target URI
    * @return a ''Props'' for the request actor for this URI
    */
  private[io] def requestActorProps(uri: Uri): Props = HttpRequestActor(uri, requestQueueSize)

  /**
    * Creates a new request actor based on the given properties. (This method
    * is mainly used for testing purposes.)
    *
    * @param props the ''Props'' for the new request actor
    * @return the new request actor
    */
  private[io] def createRequestActor(props: Props): ActorRef = context.actorOf(props)

  /**
    * Obtains the actor to handle the request for the given URI. If an actor
    * for the current host is not yet contained in the cache, it is created
    * now. In any case, the LRU cache is updated accordingly.
    *
    * @param uri the URI of the current request
    * @return the actor to handle this request
    */
  private def fetchRequestActorForUri(uri: Uri): ActorRef = {
    val authority = uri.authority
    requestActors get authority match {
      case Some(actor) => actor
      case None =>
        log.info("Creating request actor for {}.", authority)
        val oldCache = requestActors.toMap
        val actor = createRequestActor(requestActorProps(uri))
        requestActors.addItem(authority, actor)
        val removedEntries = oldCache -- requestActors.keySet
        removedEntries.values.foreach(context.stop)
        actor
    }
  }
}
