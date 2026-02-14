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

package de.oliver_heger.linedj.io.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.reflect.ClassTag

/**
  * An object providing a [[Flow]] stage implementation that filters incoming
  * data based on its type. This is especially useful when dealing with union
  * types or hierarchies of sealed classes to implement type-specific
  * processing logic.
  */
object FilterInstanceOfStage:
  /**
    * Create a new instance of this stage which accepts arbitrary input objects
    * and allows only those of the specified type to pass. The output port is
    * of the correct type, so that no casting is required.
    *
    * @param ct the implicit class tag
    * @tparam T the type of the objects that can pass the filter
    * @return the filtering flow stage
    */
  def apply[T](using ct: ClassTag[T]): Flow[Any, T, NotUsed] =
    val clazz = ct.runtimeClass.asInstanceOf[Class[T]]
    Flow[Any].filter(clazz.isInstance)
      .map(clazz.cast)
