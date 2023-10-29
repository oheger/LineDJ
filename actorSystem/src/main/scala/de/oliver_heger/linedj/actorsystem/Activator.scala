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

package de.oliver_heger.linedj.actorsystem

import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.osgi.ActorSystemActivator
import org.osgi.framework.BundleContext

object Activator {
  /**
    * Constant for a system property that defines the name of the actor
    * system. The property is evaluated by ''getActorSystemName()''.
    */
  val PropActorSystemName = "LineDJ_ActorSystemName"

  /** The default name of the actor system. */
  val DefaultActorSystemName = "LineDJ_PlatformActorSystem"
}

/**
  * A bundle activator which registers the central client-side actor system as
  * OSGi service.
  *
  * This class uses functionality provided by the Akka OSGi integration. Akka
  * already creates and configures a working actor system and passes it to a
  * method implemented by this class. This method just registers the actor
  * system in the OSGi registry so that it can be used by other components of
  * the LineDJ client platform.
  *
  * Some components have a dependency on this actor system. They can start
  * automatically as soon as this object becomes available.
  */
class Activator extends ActorSystemActivator with SystemPropertyAccess {
  import Activator._

  /**
    * @inheritdoc This implementation just registers the actor system as a
    *             service.
    */
  override def configure(context: BundleContext, system: ActorSystem): Unit = {
    context.registerService(classOf[ActorSystem], system, null)
  }

  /**
    * @inheritdoc This implementation checks whether the name of the actor
    *             system is defined as a system property. Otherwise, a default
    *             name is returned.
    */
  override def getActorSystemName(context: BundleContext): String =
  getSystemProperty(PropActorSystemName) getOrElse DefaultActorSystemName
}
