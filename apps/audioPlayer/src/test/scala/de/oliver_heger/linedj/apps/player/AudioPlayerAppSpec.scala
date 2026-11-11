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

package de.oliver_heger.linedj.apps.player

import de.oliver_heger.linedj.platform.app.*
import de.oliver_heger.linedj.platform.audio2.playlist.{MediaFileInfoResolverService, Playlist, PlaylistService}
import de.oliver_heger.linedj.platform.bus.MessageBusRegistration
import net.sf.jguiraffe.gui.builder.action.ActionStore
import org.apache.commons.configuration.Configuration
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

object AudioPlayerAppSpec:
  /** Test value for the rotation speed configuration property. */
  private val RotationSpeed = 11
end AudioPlayerAppSpec

/**
  * Test class for [[AudioPlayerApp]].
  */
class AudioPlayerAppSpec extends AnyFlatSpec, Matchers, MockitoSugar, ApplicationTestSupport:

  import AudioPlayerAppSpec.*

  /**
    * @inheritdoc This implementation adds properties to the configuration to
    *             test whether the player configuration is constructed
    *             correctly.
    */
  override def createClientApplicationContext(config: Configuration): ClientApplicationContext =
    val ctx = super.createClientApplicationContext()
    ctx.managementConfiguration.addProperty(AudioPlayerConfig.PropRotationSpeed, RotationSpeed)
    ctx

  /**
    * Creates a new test application with mocks for the services the
    * application depends on.
    *
    * @return the test application instance
    */
  private def createApp(): AudioPlayerAppTestImpl =
    val playlistService = mock[PlaylistService[Playlist, String]]
    val resolverService = mock[MediaFileInfoResolverService]

    val app = AudioPlayerAppTestImpl(playlistService, resolverService)
    app.initPlaylistService(playlistService)
    app.initResolverService(resolverService)
    app

  /**
    * Obtains the bean for the UI controller from the current application
    * context.
    *
    * @param application the application
    * @return the UI controller bean
    */
  private def queryController(application: AudioPlayerAppTestImpl): Controller =
    queryBean[Controller](application.getMainWindowBeanContext, "uiController")

  "An AudioPlayerApp" should "construct an instance correctly" in :
    val app = new AudioPlayerApp

    app shouldBe a[ApplicationAsyncStartup]
    app.appName should be(AudioPlayerApp.ApplicationName)

  it should "define a message bus registration bean" in :
    val application = activateApp(createApp())

    val busReg = queryBean[MessageBusRegistration](
      application.getMainWindowBeanContext,
      ClientApplication.BeanMessageBusRegistration
    )
    val controller = queryController(application)
    busReg.listeners should contain only controller

  it should "disable all player actions on startup" in :
    val application = activateApp(createApp())

    val actionStore = queryBean[ActionStore](application.getMainWindowBeanContext, "ACTION_STORE")
    import scala.jdk.CollectionConverters.*
    val playerActions = actionStore.getActions(
      actionStore.getActionNamesForGroup(Controller.PlayerActionGroup)
    ).asScala
    playerActions.size should be > 0
    playerActions.forall(!_.isEnabled) shouldBe true

  it should "create a bean for the configuration in the application context" in :
    val application = activateApp(createApp())

    val config = queryBean[AudioPlayerConfig](application, AudioPlayerApp.BeanPlayerConfig)
    config.rotationSpeed should be(RotationSpeed)

  it should "create a bean for the playlist service" in :
    val app = activateApp(createApp())

    queryBean[PlaylistService[Playlist, String]](
      app,
      AudioPlayerApp.BeanPlaylistService
    ) should be(app.playlistService)

  it should "create a bean for the media file resolver service" in :
    val app = activateApp(createApp())

    queryBean[MediaFileInfoResolverService](app, AudioPlayerApp.BeanResolverService) should be(app.resolverService)

/**
  * A test application implementation that starts up synchronously.
  *
  * @param playlistService the mock for the playlist service
  * @param resolverService the mock for the resolver service
  */
private class AudioPlayerAppTestImpl(val playlistService: PlaylistService[Playlist, String],
                                     val resolverService: MediaFileInfoResolverService)
  extends AudioPlayerApp, ApplicationSyncStartup, AppWithTestPlatform
