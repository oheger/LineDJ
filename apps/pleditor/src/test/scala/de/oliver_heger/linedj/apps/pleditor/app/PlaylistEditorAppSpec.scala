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

package de.oliver_heger.linedj.apps.pleditor.app

import de.oliver_heger.linedj.apps.pleditor.PlaylistActionEnabler
import de.oliver_heger.linedj.apps.pleditor.reorder.ReorderService
import de.oliver_heger.linedj.platform.app.*
import de.oliver_heger.linedj.platform.audio2.playlist.{MediaFileInfoResolverService, Playlist, PlaylistService}
import de.oliver_heger.linedj.platform.comm.MessageBus
import org.apache.pekko.actor.Actor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''PlaylistEditorApp''.
  */
class PlaylistEditorAppSpec extends AnyFlatSpec with Matchers with MockitoSugar with ApplicationTestSupport:
  /**
    * Creates a new test application instance and starts it up. This instance
    * can then be used to test whether initialization was correctly.
    *
    * @return the instance of the application
    */
  private def createApp(): PlaylistEditorAppTestImpl =
    val reorderService = mock[ReorderService]
    val playlistService = mock[PlaylistService[Playlist, String]]
    val resolverService = mock[MediaFileInfoResolverService]
    val app = new PlaylistEditorAppTestImpl(reorderService, playlistService, resolverService)
    app.initReorderService(reorderService)
    app.initPlaylistService(playlistService)
    app.initResolverService(resolverService)
    activateApp(app)

  "A PlaylistEditorApp" should "register message bus listeners correctly" in :
    val application = createApp()

    val uiBus = queryBean[MessageBus](application, ClientApplication.BeanMessageBus)
    verify(uiBus, Mockito.atLeast(1)).registerListener(any(classOf[Actor.Receive]))

  it should "construct an instance correctly" in :
    val app = new PlaylistEditorApp

    app shouldBe a[ApplicationAsyncStartup]
    app.appName should be("pleditor")

  it should "create a PlaylistActionEnabler bean for the main window" in :
    val app = createApp()

    val enabler = queryBean[PlaylistActionEnabler](app.getMainWindowBeanContext, "playlistActionEnabler")
    enabler.manipulatorMap.keySet should contain allOf("plRemoveAction", "plMoveUpAction",
      "plMoveDownAction", "plMoveTopAction", "plMoveBottomAction")

  it should "create a bean for the reorder service" in :
    val app = createApp()

    queryBean[ReorderService](app, PlaylistEditorApp.BeanReorderService) should be(app.reorderService)

  it should "create a bean for the playlist service" in :
    val app = createApp()

    queryBean[PlaylistService[Playlist, String]](
      app,
      PlaylistEditorApp.BeanPlaylistService
    ) should be(app.playlistService)

  it should "create a bean for the media file resolver service" in :
    val app = createApp()

    queryBean[MediaFileInfoResolverService](app, PlaylistEditorApp.BeanResolverService) should be(app.resolverService)

/**
  * A test implementation of the main application class that makes sure that
  * builder scripts are executed against the test platform.
  *
  * @param reorderService  the [[ReorderService]] mock
  * @param playlistService the [[PlaylistService]] mock
  * @param resolverService the [[MediaFileInfoResolverService]]
  */
private class PlaylistEditorAppTestImpl(val reorderService: ReorderService,
                                        val playlistService: PlaylistService[Playlist, String],
                                        val resolverService: MediaFileInfoResolverService)
  extends PlaylistEditorApp with ApplicationSyncStartup with AppWithTestPlatform
