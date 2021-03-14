/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.platform.app.oneforall

import de.oliver_heger.linedj.platform.app.ClientApplication
import net.sf.jguiraffe.gui.builder.window.Window
import org.osgi.service.component.ComponentContext
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''OneForAllShutdownAppManager''.
  */
class OneForAllShutdownAppManagerSpec extends AnyFlatSpec with Matchers {
  "A OneForAllShutdownManager" should "trigger shutdown on application shutdown" in {
    val manager = new OneForAllShutdownAppManagerTestImpl

    manager onApplicationShutdown null
    manager.shutdownCount should be(1)
  }

  it should "trigger shutdown on window closing" in {
    val manager = new OneForAllShutdownAppManagerTestImpl

    manager onWindowClosing null
    manager.shutdownCount should be(1)
  }

  it should "correctly activate the component" in {
    val manager = new OneForAllShutdownAppManagerTestImpl

    manager activate null
    manager.setUpCount should be(1)
  }

  it should "correctly deactivate the component" in {
    val manager = new OneForAllShutdownAppManagerTestImpl

    manager deactivate null
    manager.tearDownCount should be(1)
  }

  /**
    * A test implementation of the shutdown manager. This class provides access
    * to protected methods and allows tracking invocations of the
    * ''triggerShutdown()'' method.
    */
  private class OneForAllShutdownAppManagerTestImpl extends OneForAllShutdownAppManager {
    /** Counter for ''triggerShutdown()'' invocations. */
    var shutdownCount: Int = 0

    /** Counter for ''setUp()'' invocations. */
    var setUpCount: Int = 0

    /** Counter for ''tearDown()'' invocations. */
    var tearDownCount: Int = 0

    /**
      * @inheritdoc Records this invocation.
      */
    override def setUp(): Unit = {
      setUpCount += 1
    }

    /**
      * @inheritdoc Records this invocation.
      */
    override def tearDown(): Unit = {
      tearDownCount += 1
    }

    /**
      * @inheritdoc Records this invocation.
      */
    override protected def triggerShutdown(): Unit = {
      shutdownCount += 1
    }

    /**
      * @inheritdoc Increases visibility of this method.
      */
    override def onApplicationShutdown(app: ClientApplication): Unit =
      super.onApplicationShutdown(app)

    /**
      * @inheritdoc Increases visibility of this method.
      */
    override def onWindowClosing(window: Window): Unit =
      super.onWindowClosing(window)

    /**
      * @inheritdoc Increases visibility of this method.
      */
    override def activate(compCtx: ComponentContext): Unit =
      super.activate(compCtx)

    /**
      * @inheritdoc Increases visibility of this method.
      */
    override  def deactivate(compCtx: ComponentContext): Unit =
      super.deactivate(compCtx)
  }

}
