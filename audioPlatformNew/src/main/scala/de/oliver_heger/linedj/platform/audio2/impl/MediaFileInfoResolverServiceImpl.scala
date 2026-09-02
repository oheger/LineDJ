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
import de.oliver_heger.linedj.platform.startup.ConfigService
import de.oliver_heger.linedj.shared.actors.CachingActor
import de.oliver_heger.linedj.shared.config.ConfigExtensions.toDuration
import org.apache.commons.configuration2.ImmutableHierarchicalConfiguration
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ActorRef, Scheduler}
import org.apache.pekko.util.Timeout
import org.osgi.service.component.ComponentContext

import java.util.concurrent.atomic.AtomicReference
import scala.compiletime.uninitialized
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

  /** The default timeout for queries to the caching actor. */
  private val DefaultCacheQueryTimeout = Timeout(30.seconds)

  /** The default size of the cache for resolved file info objects. */
  private val DefaultCacheSize = 1000

  /** The prefix for all configuration keys supported by this class. */
  private val ConfigSection = "platform.infoResolver."

  /**
    * The configuration key for the timeout to be used when querying the
    * caching actor.
    */
  final val PropertyCacheQueryTimeout = ConfigSection + "cacheQueryTimeout"

  /**
    * The configuration key for the maximum size of the cache for resolved file
    * info objects.
    */
  final val PropertyCacheSize = ConfigSection + "cacheSize"

  /**
    * The configuration key for the maximum number of parallel requests when
    * multiple file infos are resolved at once.
    */
  final val PropertyQueryParallelism = ConfigSection + "queryParallelism"
end MediaFileInfoResolverServiceImpl

/**
  * The implementation of the [[MediaFileInfoResolverService]].
  *
  * This class uses an [[ArchiveService]] to query information about media 
  * files. The results are stored in a caching actor.
  *
  * @param factory the factory for creating the caching actor
  */
class MediaFileInfoResolverServiceImpl(factory: CachingActor.Factory)
  extends PlatformComponent, ClientContextSupport, ActorClientSupport,
    MediaFileInfoResolverService, ArchiveModel.ArchiveJsonSupport:

  /** The default constructor needed by OSGi. */
  def this() = this(CachingActor.newInstance)

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
    * The platform configuration. This field is set by [[initConfigService]].
    * It is accessed only from the OSGi management thread (the configuration is
    * queried during activation), so no special synchronization is needed.
    */
  private var config: ImmutableHierarchicalConfiguration = uninitialized

  /**
    * The timeout for queries to the caching actor. This value is set on the
    * OSGi management thread during activation and read from arbitrary caller
    * threads; therefore, an atomic reference is used for synchronization.
    */
  private val refCacheQueryTimeout = new AtomicReference[Timeout]

  /**
    * Initializes the [[ArchiveService]]. This function is called by the 
    * declarative services runtime.
    *
    * @param service the archive service
    */
  def initArchiveService(service: ArchiveService): Unit =
    refArchiveService.set(service)

  /**
    * Initializes the [[ConfigService]] to obtain the platform configuration.
    * This function is called by the declarative services runtime. Some
    * settings of this component are read from the configuration.
    *
    * @param configService the configuration service
    */
  def initConfigService(configService: ConfigService): Unit =
    config = configService.config

  override def activate(compContext: ComponentContext): Unit =
    super.activate(compContext)
    refCacheQueryTimeout.set(queryCacheTimeout())
    val cacheStore = CachingActor.lruStore[String, ArchiveModel.MediaFileInfo](cacheSize())
    val cachingActor = clientApplicationContext.actorFactory.createTypedActor(
      factory.apply(queryFileInfo, cacheStore, queryParallelism()),
      CachingActorName
    )
    refCachingActor.set(cachingActor)

  override def deactivate(compContext: ComponentContext): Unit =
    Option(refCachingActor.get()).foreach(_ ! CachingActor.CacheCommand.Stop())

  override def resolveFileIDs(ids: Iterable[String])
                             (callback: Try[Map[String, ArchiveModel.MediaFileInfo]] => Unit): Unit =
    given Timeout = refCacheQueryTimeout.get()
    import org.apache.pekko.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem
    given Scheduler = schedulerFromActorSystem(actorSystem.toTyped)

    refCachingActor.get().getMultiple(ids)
      .filter(_.failures.isEmpty)
      .map(_.resolved)
      .onCompleteUIThread(callback)

  /**
    * Determines the timeout for queries to the caching actor. If defined, the
    * value is obtained from the platform configuration; otherwise, a default
    * value is used.
    *
    * @return the timeout
    */
  private def queryCacheTimeout(): Timeout =
    Option(config.getString(PropertyCacheQueryTimeout))
      .flatMap(_.toDuration.toOption)
      .map(Timeout(_))
      .getOrElse(DefaultCacheQueryTimeout)

  /**
    * Determines the maximum size of the cache for resolved file info objects.
    * If configured, the value is obtained from the platform configuration;
    * otherwise, a default value is used.
    *
    * @return the cache size
    */
  private def cacheSize(): Int =
    config.getInt(PropertyCacheSize, DefaultCacheSize)

  /**
    * Determines the maximum number of parallel requests when resolving multiple
    * file infos. If the corresponding configuration property is defined, its
    * value is used; otherwise, _None_ is returned, indicating that no limit
    * should be applied.
    *
    * @return an [[Option]] with the parallelism limit
    */
  private def queryParallelism(): Option[Int] =
    Option(config.getInteger(PropertyQueryParallelism, null)).map(_.intValue())

  /**
    * Queries the info object for the file with the given ID from the archive
    * service.
    *
    * @param id the file ID
    * @return a [[Future]] with the resolved info
    */
  private def queryFileInfo(id: String): Future[ArchiveModel.MediaFileInfo] =
    refArchiveService.get().queryData[ArchiveModel.MediaFileInfo](FileInfoURLPrefix + id + FileInfoURLSuffix)
