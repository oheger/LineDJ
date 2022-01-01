/*
 * Copyright 2015-2022 The Developers Team.
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

package de.oliver_heger.linedj.platform.mediaifc.remote

import de.oliver_heger.linedj.platform.app.{ClientApplication, ClientApplicationContext}
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import net.sf.jguiraffe.gui.app.Application
import net.sf.jguiraffe.gui.builder.window.ctrl.{FormController, FormControllerFormEvent}
import org.apache.commons.configuration.{Configuration, PropertiesConfiguration}
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''RemoteMediaIfcConfigBean''.
  */
class RemoteMediaIfcConfigBeanSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  /**
    * Creates a mock application which is needed to initialize a config bean.
    *
    * @param config the configuration to be returned by the application
    * @return the mock application
    */
  private def createApplication(config: Configuration): ClientApplication = {
    val app = mock[ClientApplication]
    val appCtx = mock[ClientApplicationContext]
    when(app.clientApplicationContext).thenReturn(appCtx)
    when(appCtx.managementConfiguration).thenReturn(config)
    app
  }

  /**
    * Creates a test bean instance that is backed by the specified
    * configuration.
    *
    * @param config the configuration
    * @return the test bean instance
    */
  private def createBean(config: Configuration): RemoteMediaIfcConfigBean =
  createBean(createApplication(config))

  /**
    * Creates a test bean instance that is initialized with the specified
    * application.
    *
    * @param app the application
    * @return the test bean instance
    */
  private def createBean(app: Application): RemoteMediaIfcConfigBean = {
    val bean = new RemoteMediaIfcConfigBean
    bean setApplication app
    bean
  }

  "A RemoteMediaIfcConfigBean" should "return the host" in {
    val Host = "www.media.org"
    val config = new PropertiesConfiguration
    config.addProperty("media.host", Host)
    val bean = createBean(config)

    bean.getHost should be(Host)
  }

  it should "return a default host if this property is undefined" in {
    val bean = createBean(new PropertiesConfiguration)

    bean.getHost should be("127.0.0.1")
  }

  it should "return the port" in {
    val Port = 7777
    val config = new PropertiesConfiguration
    config.addProperty("media.port", Port)
    val bean = createBean(config)

    bean.getPort should be(Port)
  }

  it should "return a default port if this property is undefined" in {
    val bean = createBean(new PropertiesConfiguration)

    bean.getPort should be(2552)
  }

  it should "set the host" in {
    val Host = "the.new.host"
    val config = new PropertiesConfiguration
    config.addProperty("media.host", "other_" + Host)
    val bean = createBean(config)

    bean setHost Host
    config getString "media.host" should be(Host)
  }

  it should "set the port" in {
    val Port = 1234
    val config = new PropertiesConfiguration
    config.setProperty("media.port", Port + 1)
    val bean = createBean(config)

    bean setPort Port
    config getInt "media.port" should be(Port)
  }

  it should "update the media interface when the form is committed" in {
    val config = new PropertiesConfiguration
    val app = createApplication(config)
    val facade = mock[MediaFacade]
    val appCtx = mock[ClientApplicationContext]
    when(app.clientApplicationContext).thenReturn(appCtx)
    when(appCtx.managementConfiguration).thenReturn(config)
    when(appCtx.mediaFacade).thenReturn(facade)
    val bean = createBean(app)

    bean formClosed new FormControllerFormEvent(mock[FormController], FormControllerFormEvent
      .Type.FORM_COMMITTED)
    verify(facade).initConfiguration(config)
  }

  it should "ignore a canceled form event" in {
    val bean = createBean(new PropertiesConfiguration)

    bean formClosed new FormControllerFormEvent(mock[FormController], FormControllerFormEvent
      .Type.FORM_CANCELED)
  }
}
