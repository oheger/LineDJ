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

package de.oliver_heger.linedj.platform.app

import net.sf.jguiraffe.di.BeanContext
import net.sf.jguiraffe.gui.app.Application
import org.mockito.Mockito
import org.mockito.Mockito._
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
    * Queries the given bean context for a bean with a specific name. This bean
    * is checked against a type. If this type is matched, the bean is returned;
    * otherwise, an exception is thrown.
    * @param context the ''BeanContext''
    * @param name the name of the bean
    * @param m the manifest
    * @tparam T the expected bean type
    * @return the bean of this type
    */
  def queryBean[T](context: BeanContext, name: String)(implicit m: Manifest[T]): T = {
    context.getBean(name) match {
      case t: T => t
      case b =>
        throw new AssertionError(s"Unexpected bean for name '$name': $b")
    }
  }

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
  def queryBean[T](app: Application, name: String)(implicit m: Manifest[T]): T =
    queryBean(app.getApplicationContext.getBeanContext, name)(m)

  /**
    * Prepares a mock bean context to support the specified bean. The context
    * mock is configured to return '''true''' for queries whether it has this
    * bean and to return the bean when it is requested.
    * @param ctx the bean context
    * @param name the name of the bean
    * @param bean the bean itself
    * @return the bean context mock
    */
  def addBean(ctx: BeanContext, name: String, bean: AnyRef): BeanContext = {
    doReturn(bean).when(ctx).getBean(name)
    when(ctx.containsBean(name)).thenReturn(true)
    ctx
  }

  /**
    * Prepares a mock bean context to support all beans defined by the given
    * map. This is a convenience method which invokes ''addBean()'' for all
    * entries contained in the map.
    * @param ctx the bean context
    * @param beans a map defining the beans to be added
    * @return the bean context mock
    */
  def addBeans(ctx: BeanContext, beans: Map[String, AnyRef]): BeanContext = {
    beans foreach(e => addBean(ctx, e._1, e._2))
    ctx
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
    * ''ClientApplicationContext'', an application manager, and a dummy exit
    * handler. Then the application's ''activate()'' method is called.
    * @param app the application
    * @tparam T the type of the application
    * @return the application again
    */
  def activateApp[T <: ClientApplication](app: T): T = {
    app initClientContext createClientApplicationContext()
    app initApplicationManager Mockito.mock(classOf[ApplicationManager])
    app setExitHandler new Runnable {
      override def run(): Unit = {
        // do nothing
      }
    }
    app activate Mockito.mock(classOf[ComponentContext])
    app
  }
}
