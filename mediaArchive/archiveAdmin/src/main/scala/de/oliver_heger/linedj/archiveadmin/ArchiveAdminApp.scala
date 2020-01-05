/*
 * Copyright 2015-2019 The Developers Team.
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

package de.oliver_heger.linedj.archiveadmin

import akka.actor.ActorRef
import akka.util.Timeout
import de.oliver_heger.linedj.platform.app.support.ActorClientSupport
import de.oliver_heger.linedj.platform.app.support.ActorClientSupport.ActorRequest
import de.oliver_heger.linedj.platform.app.{ApplicationAsyncStartup, ClientApplication}
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaFacadeActors
import org.osgi.service.component.ComponentContext

import scala.concurrent.duration._

object ArchiveAdminApp {
  /**
    * Configuration property for the timeout value to be applied for
    * invocations of the meta data actor.
    */
  val PropActorTimeout = "actorTimeout"

  /** The default timeout value for actor invocations (in seconds). */
  private val DefaultActorTimeout = 10
}

/**
  * Main class of LineDJ Archive Admin application.
  */
class ArchiveAdminApp extends ClientApplication("archiveAdmin") with ApplicationAsyncStartup
  with ActorClientSupport {

  import ArchiveAdminApp._

  /** The actors for the media facade. */
  private var mediaFacadeActorsField: MediaFacadeActors = _

  /**
    * The implicit timeout for actor invocations. This property is initialized
    * from the application configuration.
    */
  private implicit var actorInvocationTimeout: Timeout = _

  /**
    * Initializes the object with the actors for the media facade. This method
    * is called by the declarative services runtime.
    *
    * @param mediaFacadeActors the media facade actors
    */
  def initFacadeActors(mediaFacadeActors: MediaFacadeActors): Unit = {
    mediaFacadeActorsField = mediaFacadeActors
  }

  /**
    * Returns the ''MediaFacadeActors'' object managed by this application.
    * This field allows access to the actors that control the union archive.
    *
    * @return the ''MediaFacadeActors'' object
    */
  def mediaFacadeActors: MediaFacadeActors = mediaFacadeActorsField

  override def activate(compContext: ComponentContext): Unit = {
    super.activate(compContext)
    val timeoutSecs = clientApplicationContext.managementConfiguration.getInt(PropActorTimeout, DefaultActorTimeout)
    actorInvocationTimeout = Timeout(timeoutSecs.seconds)
  }

  /**
    * Helper function to send a request to an actor. The given message is sent
    * to the actor using the timeout from the application configuration.
    *
    * @param actor the actor to be invoked
    * @param msg   the message to be sent
    * @return an object representing the actor request
    */
  def invokeActor(actor: ActorRef, msg: Any): ActorRequest = actorRequest(actor, msg)
}
