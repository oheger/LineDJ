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

package de.oliver_heger.linedj.platform.audio.impl

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import de.oliver_heger.linedj.platform.app.ClientApplicationContext
import de.oliver_heger.linedj.platform.app.support.ActorManagement
import de.oliver_heger.linedj.player.engine.PlayerConfig
import de.oliver_heger.linedj.player.engine.PlayerConfig.ActorCreator
import org.apache.commons.configuration.PropertiesConfiguration
import org.mockito.Matchers.{any, eq => eqArg}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

/**
  * Test class for ''AudioPlayerFactory''.
  */
class AudioPlayerFactorySpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("AudioPlayerFactorySpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "An AudioPlayerFactory" should "create a correct default config factory" in {
    val factory = new AudioPlayerFactory

    factory.playerConfigFactory should not be null
  }

  it should "create a correct audio player" in {
    val configFactory = mock[PlayerConfigFactory]
    val mediaManager = TestProbe()
    val Prefix = "audio.platform.config"
    val BufSize = 8888
    val appConfig = new PropertiesConfiguration
    val actorCreations = collection.mutable.Map.empty[String, Props]
    val management = new ActorManagement {
      override def initClientContext(context: ClientApplicationContext): Unit = {}

      override def clientApplicationContext: ClientApplicationContext = null

      /**
        * @inheritdoc Records this actor creation.
        */
      override def createAndRegisterActor(props: Props, name: String): ActorRef = {
        actorCreations += name -> props
        TestProbe().ref
      }
    }
    when(configFactory.createPlayerConfig(eqArg(appConfig), eqArg(Prefix),
      eqArg(mediaManager.ref), any())).thenAnswer(new Answer[PlayerConfig] {
      override def answer(invocation: InvocationOnMock): PlayerConfig = {
        val creator = invocation.getArguments()(3).asInstanceOf[ActorCreator]
        PlayerConfig(inMemoryBufferSize = BufSize, mediaManagerActor = mediaManager.ref,
          actorCreator = creator)
      }
    })
    val factory = new AudioPlayerFactory(configFactory)

    val player = factory.createAudioPlayer(appConfig, Prefix, mediaManager.ref, management)
    player should not be null
    val facadeProps = actorCreations("playerFacadeActor")
    val actConfig = facadeProps.args.head.asInstanceOf[PlayerConfig]
    actConfig.mediaManagerActor should be(mediaManager.ref)
    actConfig.inMemoryBufferSize should be(BufSize)
  }
}
