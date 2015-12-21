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

import net.sf.jguiraffe.gui.app.Application
import org.mockito.Mockito
import org.osgi.service.component.ComponentContext

/**
  * A trait defining functionality useful for testing application classes.
  *
  * In test specs for classes derived from ''Application'' some functionality
  * is needed again and again. This helper trait defines a couple of helper
  * methods that simplify such tests.
  */
trait ApplicationTestSupport {
  /**
    * Queries the given application for a bean with a specific name. This bean
    * is checked against a type. If this type is matched, the bean is returned;
    * otherwise, an exception is thrown.
    * @param app the application
    * @param name the name of the bean
    * @param m the manifest
    * @tparam T the expected bean type
    * @return the bean of this type
    */
  def queryBean[T](app: Application, name: String)(implicit m: Manifest[T]): T = {
    app.getApplicationContext.getBeanContext.getBean(name) match {
      case t: T => t
      case b =>
        throw new AssertionError(s"Unexpected bean for name '$name': $b")
    }
  }

  /**
    * Creates a ''ClientApplicationContext'' object which provides mock
    * objects.
    * @return the ''ClientApplicationContext''
    */
  def createClientApplicationContext(): ClientApplicationContext =
    new ClientApplicationContextImpl

  /**
    * Initializes and activates the specified application. This method sets the
    * ''ClientApplicationContext'' and a dummy exit handler. Then the
    * application's ''activate()'' method is called.
    * @param app the application
    * @tparam T the type of the application
    * @return the application again
    */
  def activateApp[T <: ClientApplication](app: T): T = {
    app initClientContext createClientApplicationContext()
    app setExitHandler new Runnable {
      override def run(): Unit = {
        // do nothing
      }
    }
    app activate Mockito.mock(classOf[ComponentContext])
    app
  }
}
