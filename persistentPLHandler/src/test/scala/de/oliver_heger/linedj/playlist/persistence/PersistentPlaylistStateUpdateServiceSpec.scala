/*
 * Copyright 2015-2018 The Developers Team.
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

package de.oliver_heger.linedj.playlist.persistence

import de.oliver_heger.linedj.platform.audio.{AudioPlayerStateChangeRegistration, AudioPlayerStateChangedEvent}
import de.oliver_heger.linedj.platform.bus.{ComponentID, ConsumerSupport}
import de.oliver_heger.linedj.platform.mediaifc.ext.AvailableMediaExtension.AvailableMediaRegistration
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediumID, MediumInfo}
import org.scalatest.{FlatSpec, Matchers}

object PersistentPlaylistStateUpdateServiceSpec extends PlaylistTestHelper {
  /** Test component ID. */
  private val TestComponent = ComponentID()

  /** A test playlist. */
  private val TestPlaylist = generateSetPlaylist(length = 42, currentIdx = 28)

  /** A state that represents an already activated component. */
  private val ActiveState = PersistentPlaylistStateUpdateServiceImpl.InitialState
    .copy(componentID = Some(TestComponent))

  /**
    * Helper method for creating a dummy callback function.
    *
    * @tparam A the type of the callback
    * @return the dummy callback function
    */
  private def createCallback[A](): ConsumerSupport.ConsumerFunction[A] =
    a => println(a)

  /**
    * Generates an ''AvailableMedia'' message that contains the specified
    * media.
    *
    * @param media the media to be contained
    * @return the resulting ''AvailableMedia'' object
    */
  private def createAvailableMedia(media: Iterable[MediumID]): AvailableMedia = {
    val mediaData = media map { m =>
      val info = MediumInfo(m.mediumURI, "desc", m, "", "", "")
      m -> info
    }
    AvailableMedia(mediaData.toMap)
  }

  /**
    * Convenience method to execute a ''State'' object to produce the updated
    * state and an additional result.
    *
    * @param s        the ''State''
    * @param oldState the original state
    * @tparam A the type of the additional result
    * @return a tuple with the updated state and the additional result
    */
  private def updateState[A](s: PersistentPlaylistStateUpdateServiceImpl.StateUpdate[A],
                             oldState: PersistentPlaylistState =
                             PersistentPlaylistStateUpdateServiceImpl.InitialState):
  (PersistentPlaylistState, A) = s(oldState)

  /**
    * Convenience method to modify a ''State'' object. This means, the state is
    * only manipulated without producing an additional result.
    *
    * @param s        the ''State''
    * @param oldState the original state
    * @return the updated state
    */
  private def modifyState(s: PersistentPlaylistStateUpdateServiceImpl.StateUpdate[Unit],
                          oldState: PersistentPlaylistState =
                          PersistentPlaylistStateUpdateServiceImpl.InitialState):
  PersistentPlaylistState = {
    val (next, _) = updateState(s, oldState)
    next
  }
}

/**
  * Test class for ''PersistentPlaylistStateUpdateServiceImpl''.
  */
class PersistentPlaylistStateUpdateServiceSpec extends FlatSpec with Matchers {

  import PersistentPlaylistStateUpdateServiceSpec._

  "A PersistentPlaylistStateUpdateService" should "define an initial state" in {
    val state = PersistentPlaylistStateUpdateServiceImpl.InitialState

    state.loadedPlaylist shouldBe 'empty
    state.componentID shouldBe 'empty
    state.referencedMediaIDs shouldBe 'empty
    state.availableMediaIDs shouldBe 'empty
    state.messages shouldBe 'empty
  }

  it should "update the state when the component is activated" in {
    val callback = createCallback[AvailableMedia]()

    val next = modifyState(PersistentPlaylistStateUpdateServiceImpl.activate(TestComponent,
      callback))
    next.componentID shouldBe Some(TestComponent)
    next.messages should contain only AvailableMediaRegistration(TestComponent, callback)
  }

  it should "ignore another activation" in {
    val state = PersistentPlaylistStateUpdateServiceImpl.InitialState
      .copy(componentID = Some(ComponentID()))

    val next = modifyState(PersistentPlaylistStateUpdateServiceImpl.activate(TestComponent,
      createCallback()), state)
    next shouldBe state
  }

  it should "update the state when a playlist arrives" in {
    val callback = createCallback[AudioPlayerStateChangedEvent]()

    val next = modifyState(PersistentPlaylistStateUpdateServiceImpl.playlistLoaded(TestPlaylist,
      callback), ActiveState)
    next.loadedPlaylist shouldBe Some(TestPlaylist)
    next.referencedMediaIDs.get should contain only (MediaIDs: _*)
    next.messages should contain only AudioPlayerStateChangeRegistration(TestComponent, callback)
  }

  it should "ignore a playlist if the component is not activated" in {
    val next = modifyState(PersistentPlaylistStateUpdateServiceImpl.playlistLoaded(TestPlaylist,
      createCallback()))

    next shouldBe PersistentPlaylistStateUpdateServiceImpl.InitialState
  }

  it should "store available media that arrive before the playlist" in {
    val av = createAvailableMedia(MediaIDs)

    val next = modifyState(PersistentPlaylistStateUpdateServiceImpl.availableMediaArrived(av))
    next.availableMediaIDs should contain only (MediaIDs: _*)
  }

  it should "activate the playlist when it arrives and all media are available" in {
    val state = ActiveState.copy(availableMediaIDs = MediaIDs.toSet)

    val next = modifyState(PersistentPlaylistStateUpdateServiceImpl.playlistLoaded(TestPlaylist,
      createCallback()), state)
    next.referencedMediaIDs shouldBe 'empty
    next.messages should have size 2
    next.messages should contain(TestPlaylist)
  }

  it should "activate the playlist when all referenced media become available" in {
    val state = ActiveState.copy(referencedMediaIDs = Some(MediaIDs.drop(1).toSet),
      loadedPlaylist = Some(TestPlaylist))

    val next = modifyState(PersistentPlaylistStateUpdateServiceImpl
      .availableMediaArrived(createAvailableMedia(MediaIDs)), state)
    next.messages should contain only TestPlaylist
    next.referencedMediaIDs shouldBe 'empty
    next.availableMediaIDs shouldBe 'empty
  }

  it should "not activate the playlist before all media are available" in {
    val state = ActiveState.copy(referencedMediaIDs = Some(MediaIDs.toSet),
      availableMediaIDs = Set(MediaIDs.head), loadedPlaylist = Some(TestPlaylist))
    val av = createAvailableMedia(MediaIDs take 2)

    val next = modifyState(PersistentPlaylistStateUpdateServiceImpl.availableMediaArrived(av),
      state)
    next.messages shouldBe 'empty
    next.availableMediaIDs shouldBe 'empty
  }

  it should "handle a state with referenced IDs, but no playlist gracefully" in {
    val state = ActiveState.copy(referencedMediaIDs = Some(MediaIDs.drop(1).toSet))

    val next = modifyState(PersistentPlaylistStateUpdateServiceImpl
      .availableMediaArrived(createAvailableMedia(MediaIDs)), state)
    next.messages shouldBe 'empty
    next.availableMediaIDs should contain only (MediaIDs: _*)
  }

  it should "fetch messages to be published" in {
    val messages = List("foo", "bar", "baz")
    val state = ActiveState.copy(messages = messages.reverse)

    val (next, res) = updateState(PersistentPlaylistStateUpdateServiceImpl.fetchMessages(),
      state)
    res shouldBe messages
    next.messages shouldBe 'empty
  }

  it should "handle the component activation" in {
    val callback = createCallback[AvailableMedia]()

    val (next, msg) = updateState(PersistentPlaylistStateUpdateServiceImpl
      .handleActivation(TestComponent, callback))
    next.componentID shouldBe Some(TestComponent)
    next.messages shouldBe 'empty
    msg should contain only AvailableMediaRegistration(TestComponent, callback)
  }

  it should "handle the arrival of the playlist" in {
    val callback = createCallback[AudioPlayerStateChangedEvent]()
    val state = ActiveState.copy(availableMediaIDs = MediaIDs.toSet)

    val (next, msg) = updateState(PersistentPlaylistStateUpdateServiceImpl.
      handlePlaylistLoaded(TestPlaylist, callback), state)
    next.referencedMediaIDs shouldBe 'empty
    msg should contain only(TestPlaylist,
      AudioPlayerStateChangeRegistration(TestComponent, callback))
    next.messages shouldBe 'empty
    next.loadedPlaylist shouldBe Some(TestPlaylist)
  }

  it should "handle the arrival of new available media" in {
    val state = ActiveState.copy(referencedMediaIDs = Some(MediaIDs.drop(1).toSet),
      loadedPlaylist = Some(TestPlaylist))

    val (next, msg) = updateState(PersistentPlaylistStateUpdateServiceImpl
      .handleNewAvailableMedia(createAvailableMedia(MediaIDs)), state)
    msg should contain only TestPlaylist
    next.messages shouldBe 'empty
    next.referencedMediaIDs shouldBe 'empty
    next.availableMediaIDs shouldBe 'empty
  }
}
