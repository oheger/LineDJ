/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.pleditor.ui.reorder

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import de.oliver_heger.linedj.platform.app.ClientApplicationContext
import de.oliver_heger.linedj.platform.audio.model.SongData
import de.oliver_heger.linedj.pleditor.spi.PlaylistReorderer
import org.apache.logging.log4j.LogManager
import org.osgi.service.component.ComponentContext

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration._

object ReorderManagerComponent {
  /** The name of the reorder manager actor. */
  private val ReorderManagerActorName = "ReorderManagerActor"
}

/**
  * A declarative services component responsible of playlist reorder
  * operations.
  *
  * Concrete playlist reorder services can be added and removed dynamically as
  * OSGi services. This component tracks such services and manages them by
  * passing them to a [[ReorderManagerActor]] instance. The [[ReorderService]]
  * interface is implemented on top of this management actor.
  */
class ReorderManagerComponent extends ReorderService {

  import ReorderManagerComponent._

  /** The logger. */
  private val log = LogManager.getLogger(getClass)

  /** The client context. */
  private var clientApplicationContext: ClientApplicationContext = _

  /** The actor for managing reorder services. */
  private var reorderManagerActor: ActorRef = _

  /**
    * Initializes the (static) reference to the ''ClientApplicationContext''.
    * This method is called by the declarative services runtime.
    *
    * @param context the ''ClientApplicationContext''
    */
  def initClientApplicationContext(context: ClientApplicationContext): Unit = {
    clientApplicationContext = context
    reorderManagerActor = context.actorFactory.createActor(ReorderManagerActor(context
      .messageBus), ReorderManagerActorName)
  }

  /**
    * Life-cycle callback when the component gets deactivated. This method is
    * called by the declarative services runtime. This implementation stops the
    * management actor.
    *
    * @param compCtx the ''ComponentContext''
    */
  def deactivate(compCtx: ComponentContext): Unit = {
    clientApplicationContext.actorFactory.actorSystem stop reorderManagerActor
  }

  /**
    * Notifies this component that a new ''PlaylistReorderer'' service has
    * become available. This method is called by the declarative services
    * runtime. It passes the service to the management actor.
    *
    * @param service the new service object
    */
  def reorderServiceAdded(service: PlaylistReorderer): Unit = {
    log.info("Added playlist reorder service.")
    try {
      reorderManagerActor ! ReorderManagerActor.AddReorderService(service, service.name)
    } catch {
      case e: Exception =>
        log.warn("Could not add reorder service!", e)
      // in this case, the service could not be asked for its name;
      // we ignore the service
    }
  }

  /**
    * Notifies this component that a ''PlaylistReorderer'' service is no longer
    * available. This method is called by the declarative services runtime. It
    * informs the management actor.
    *
    * @param service the removed service object
    */
  def reorderServiceRemoved(service: PlaylistReorderer): Unit = {
    reorderManagerActor ! ReorderManagerActor.RemoveReorderService(service)
  }

  /**
    * @inheritdoc This implementation asks the management actor.
    */
  override def loadAvailableReorderServices(): Future[Seq[(PlaylistReorderer, String)]] = {
    implicit val timeout: Timeout = Timeout(10.seconds)
    implicit val ec: ExecutionContextExecutor = clientApplicationContext.actorSystem.dispatcher
    val future = reorderManagerActor ? ReorderManagerActor.GetAvailableReorderServices
    future.mapTo[ReorderManagerActor.AvailableReorderServices].map(_.services)
  }

  /**
    * @inheritdoc This implementation sends a corresponding request to the
    *             management actor.
    */
  override def reorder(service: PlaylistReorderer, songs: Seq[SongData], startIdx: Int): Unit = {
    reorderManagerActor ! ReorderManagerActor.ReorderServiceInvocation(service, ReorderActor
      .ReorderRequest(songs, startIdx))
  }
}
