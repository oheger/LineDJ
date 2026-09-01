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

package de.oliver_heger.linedj.platform.audio2.impl

import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.platform.app.support.ActorClientSupport
import de.oliver_heger.linedj.platform.app.{ClientContextSupport, PlatformComponent}
import de.oliver_heger.linedj.platform.archiveclient.ArchiveService
import de.oliver_heger.linedj.platform.audio2.playlist.MediaFileInfoResolverService
import de.oliver_heger.linedj.shared.actors.CachingActor
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ActorRef, Scheduler}
import org.apache.pekko.util.Timeout
import org.osgi.service.component.ComponentContext

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Try

object MediaFileInfoResolverServiceImpl:
  /** The URL prefix for requests for file info objects. */
  private val FileInfoURLPrefix = "/api/archive/files/"

  /** The URL suffix for requests for file info objects. */
  private val FileInfoURLSuffix = "/info"

  /** The name of the actor for caching resolved infos. */
  private val CachingActorName = "MediaFileInfoResolverCache"

  /** The timeout for queries to the caching actor. */
  private val CacheQueryTimeout = Timeout(10.seconds)
end MediaFileInfoResolverServiceImpl

/**
  * The implementation of the [[MediaFileInfoResolverService]].
  *
  * This class uses an [[ArchiveService]] to query information about media 
  * files. The results are stored in a caching actor.
  */
class MediaFileInfoResolverServiceImpl extends PlatformComponent, ClientContextSupport, ActorClientSupport,
  MediaFileInfoResolverService, ArchiveModel.ArchiveJsonSupport:

  import MediaFileInfoResolverServiceImpl.*

  /** Stores the actor with the cache of already retrieved info objects. */
  private val refCachingActor =
    new AtomicReference[ActorRef[CachingActor.CacheCommand[String, ArchiveModel.MediaFileInfo]]]

  /**
    * Stores the archive service when it is provided by the declarative 
    * services runtime.
    */
  private val refArchiveService = new AtomicReference[ArchiveService]

  /**
    * Initializes the [[ArchiveService]]. This function is called by the 
    * declarative services runtime.
    *
    * @param service the archive service
    */
  def initArchiveService(service: ArchiveService): Unit =
    refArchiveService.set(service)

  override def activate(compContext: ComponentContext): Unit =
    super.activate(compContext)
    val cachingActor = clientApplicationContext.actorFactory.createTypedActor(
      CachingActor.newInstance(queryFileInfo),
      CachingActorName
    )
    refCachingActor.set(cachingActor)

  override def deactivate(compContext: ComponentContext): Unit =
    Option(refCachingActor.get()).foreach(_ ! CachingActor.CacheCommand.Stop())

  override def resolveFileIDs(ids: Iterable[String])
                             (callback: Try[Map[String, ArchiveModel.MediaFileInfo]] => Unit): Unit =
    given Timeout = CacheQueryTimeout
    import org.apache.pekko.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem
    given Scheduler = schedulerFromActorSystem(actorSystem.toTyped)

    refCachingActor.get().getMultiple(ids)
      .filter(_.failures.isEmpty)
      .map(_.resolved)
      .onCompleteUIThread(callback)

  /**
    * Queries the info object for the file with the given ID from the archive
    * service.
    *
    * @param id the file ID
    * @return a [[Future]] with the resolved info
    */
  private def queryFileInfo(id: String): Future[ArchiveModel.MediaFileInfo] =
    refArchiveService.get().queryData[ArchiveModel.MediaFileInfo](FileInfoURLPrefix + id + FileInfoURLSuffix)
