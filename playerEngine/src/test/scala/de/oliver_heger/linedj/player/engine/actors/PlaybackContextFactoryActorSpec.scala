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

package de.oliver_heger.linedj.player.engine.actors

import de.oliver_heger.linedj.player.engine.{PlaybackContext, PlaybackContextFactory}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.testkit.{ImplicitSender, TestKit}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{verify, verifyNoInteractions, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.InputStream

/**
  * Test class for [[PlaybackContextFactoryActor]].
  */
class PlaybackContextFactoryActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar:
  def this() = this(ActorSystem("PlaybackContextFactoryActorSpec"))

  /** A test URI. */
  private val Uri = "Some test URI to a test file"

  /** The test kit for testing typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit =
    testKit.shutdownTestKit()
    TestKit shutdownActorSystem system
    super.afterAll()

  /**
    * Returns a mock for a factory. The mock is initialized to return None.
    *
    * @return the mock sub factory
    */
  private def createFactory(): PlaybackContextFactory =
    val subFactory = mock[PlaybackContextFactory]
    when(subFactory.createPlaybackContext(any(classOf[InputStream]), anyString())).thenReturn(None)
    subFactory

  "PlaybackContextFactoryActor" should "return None if there are no factories" in:
    val actor = testKit.spawn(PlaybackContextFactoryActor())

    actor ! PlaybackContextFactoryActor.CreatePlaybackContext(mock[InputStream], Uri, testActor)

    expectMsg(PlaybackContextFactoryActor.CreatePlaybackContextResult(None))

  it should "invoke the available factories" in:
    val factory1 = createFactory()
    val factory2 = createFactory()
    val stream = mock[InputStream]
    val actor = testKit.spawn(PlaybackContextFactoryActor())

    actor ! PlaybackContextFactoryActor.AddPlaybackContextFactory(factory1)
    actor ! PlaybackContextFactoryActor.AddPlaybackContextFactory(factory2)
    actor ! PlaybackContextFactoryActor.CreatePlaybackContext(stream, Uri, testActor)

    expectMsg(PlaybackContextFactoryActor.CreatePlaybackContextResult(None))
    verify(factory1).createPlaybackContext(stream, Uri)
    verify(factory2).createPlaybackContext(stream, Uri)

  it should "return the first valid playback context" in:
    val factory1 = createFactory()
    val factory2 = createFactory()
    val factory3 = createFactory()
    val stream = mock[InputStream]
    val context = mock[PlaybackContext]
    when(factory2.createPlaybackContext(stream, Uri)).thenReturn(Some(context))
    val actor = testKit.spawn(PlaybackContextFactoryActor())

    actor ! PlaybackContextFactoryActor.AddPlaybackContextFactory(factory1)
    actor ! PlaybackContextFactoryActor.AddPlaybackContextFactory(factory2)
    actor ! PlaybackContextFactoryActor.AddPlaybackContextFactory(factory3)
    actor ! PlaybackContextFactoryActor.CreatePlaybackContext(stream, Uri, testActor)

    expectMsg(PlaybackContextFactoryActor.CreatePlaybackContextResult(Some(context)))
    verify(factory2).createPlaybackContext(stream, Uri)
    verifyNoInteractions(factory1)

  it should "support removing a factory" in:
    val factory1 = createFactory()
    val factory2 = createFactory()
    val stream = mock[InputStream]
    val actor = testKit.spawn(PlaybackContextFactoryActor())

    actor ! PlaybackContextFactoryActor.AddPlaybackContextFactory(factory1)
    actor ! PlaybackContextFactoryActor.AddPlaybackContextFactory(factory2)
    actor ! PlaybackContextFactoryActor.RemovePlaybackContextFactory(factory2)
    actor ! PlaybackContextFactoryActor.CreatePlaybackContext(stream, Uri, testActor)

    expectMsg(PlaybackContextFactoryActor.CreatePlaybackContextResult(None))
    verifyNoInteractions(factory2)

  it should "stop itself on receiving a Stop command" in:
    val actor = testKit.spawn(PlaybackContextFactoryActor())

    actor ! PlaybackContextFactoryActor.Stop

    val probeWatcher = testKit.createDeadLetterProbe()
    probeWatcher.expectTerminated(actor)
