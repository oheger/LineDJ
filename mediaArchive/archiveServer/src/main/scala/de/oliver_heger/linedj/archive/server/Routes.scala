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

package de.oliver_heger.linedj.archive.server

import de.oliver_heger.linedj.archive.server.content.ArchiveContentActor
import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import org.apache.pekko.actor as classics
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.{Directives, Route}
import org.apache.pekko.util.Timeout

/**
  * An object defining the routes supported by the archive server.
  *
  * The routes allow accessing the content of the archive in various ways.
  */
object Routes extends ArchiveModel.ArchiveJsonSupport:
  /**
    * Returns the top-level route of the archive server.
    *
    * @param config       the configuration for the server
    * @param contentActor the actor managing the content of the archive
    * @return the top-level route of the server
    */
  def route(config: ArchiveServerConfig, contentActor: ActorRef[ArchiveContentActor.ArchiveContentCommand])
           (using system: classics.ActorSystem): Route =
    given ActorSystem[Nothing] = system.toTyped

    given Timeout(config.timeout)
    import org.apache.pekko.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem

    pathPrefix("api"):
      pathPrefix("archive"):
        pathPrefix("media"):
          get:
            val futMediaOverview = contentActor.ask[ArchiveContentActor.GetMediaResponse](
              ArchiveContentActor.ArchiveContentCommand.GetMedia(_)
            )
            onSuccess(futMediaOverview): mediaResponse =>
              complete(ArchiveModel.MediaOverview(mediaResponse.media))
