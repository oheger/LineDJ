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

package de.oliver_heger.linedj.player.ui

import de.oliver_heger.linedj.platform.app.{AppWithTestPlatform, ApplicationAsyncStartup, ApplicationSyncStartup, ApplicationTestSupport, ClientApplication, ClientApplicationContext}
import de.oliver_heger.linedj.platform.bus.MessageBusRegistration
import de.oliver_heger.linedj.platform.mediaifc.ext.ConsumerRegistrationProcessor
import net.sf.jguiraffe.gui.builder.action.ActionStore
import org.apache.commons.configuration.Configuration
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object AudioPlayerAppSpec:
  /** Test value for the rotation speed configuration property. */
  private val RotationSpeed = 11

/**
  * Test class for ''AudioPlayerApp''.
  */
class AudioPlayerAppSpec extends AnyFlatSpec with Matchers with ApplicationTestSupport:
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
    * Obtains the bean for the UI controller from the current application
    * context.
    *
    * @param application the application
    * @return the UI controller bean
    */
  private def queryController(application: AudioPlayerAppTestImpl): UIController =
    queryBean[UIController](application.getMainWindowBeanContext,
      "uiController")

  "An AudioPlayerApp" should "construct an instance correctly" in:
    val app = new AudioPlayerApp

    app shouldBe a[ApplicationAsyncStartup]
    app.appName should be("audioPlayer")

  it should "define a correct consumer registration bean" in:
    val application = activateApp(new AudioPlayerAppTestImpl)

    val consumerReg = queryBean[ConsumerRegistrationProcessor](application
      .getMainWindowBeanContext, ClientApplication.BeanConsumerRegistration)
    val controller = queryController(application)
    consumerReg.providers should contain only controller

  it should "define a message bus registration bean" in:
    val application = activateApp(new AudioPlayerAppTestImpl)

    val busReg = queryBean[MessageBusRegistration](application.getMainWindowBeanContext,
      ClientApplication.BeanMessageBusRegistration)
    val controller = queryController(application)
    busReg.listeners should contain only controller

  it should "disable all player actions on startup" in:
    val application = activateApp(new AudioPlayerAppTestImpl)

    val actionStore = queryBean[ActionStore](application.getMainWindowBeanContext, "ACTION_STORE")
    import scala.jdk.CollectionConverters._
    val playerActions = actionStore.getActions(
      actionStore.getActionNamesForGroup(UIController.PlayerActionGroup)).asScala
    playerActions.size should be > 0
    playerActions.forall(!_.isEnabled) shouldBe true

  it should "create a bean for the configuration in the application context" in:
    val application = activateApp(new AudioPlayerAppTestImpl)

    val config = queryBean[AudioPlayerConfig](application, AudioPlayerApp.BeanPlayerConfig)
    config.rotationSpeed should be(RotationSpeed)

  /**
    * A test application implementation that starts up synchronously.
    */
  private class AudioPlayerAppTestImpl extends AudioPlayerApp with ApplicationSyncStartup with AppWithTestPlatform
