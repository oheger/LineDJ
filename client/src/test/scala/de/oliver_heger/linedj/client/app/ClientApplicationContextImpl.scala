/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.client.app

import akka.actor.ActorSystem
import de.oliver_heger.linedj.client.remoting.{ActorFactory, MessageBus, RemoteMessageBus}
import net.sf.jguiraffe.gui.platform.javafx.builder.window.{StyleSheetProvider, DefaultStageFactory}
import org.scalatest.mock.MockitoSugar

object ClientApplicationContextImpl {
  /**
    * A shared ''StageFactory'' which can be used by all tests that have to
    * setup a UI. Using a shared ''DefaultStageFactory'' guarantees that a
    * JavaFX application is launched exactly once.
    */
  private val SharedStageFactory = DefaultStageFactory(new StyleSheetProvider(""))
}

/**
  * An implementation of ''ClientApplicationContext'' which stores a bunch of
  * mock objects.
  */
class ClientApplicationContextImpl extends ClientApplicationContext with MockitoSugar {
  override val actorSystem = mock[ActorSystem]

  override val remoteMessageBus = mock[RemoteMessageBus]

  override val messageBus = mock[MessageBus]

  override val stageFactory = ClientApplicationContextImpl.SharedStageFactory

  override val actorFactory = mock[ActorFactory]
}
