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

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers

object FilterInstanceOfStageSpec:
  /**
    * A test class to be used for testing the filtering logic.
    *
    * @param msg some message
    */
  private case class Foo(msg: String)

  /**
    * Another test class to be used for testing the filtering logic.
    *
    * @param value some value
    */
  private case class Bar(value: Int)

  /**
    * A union type combining the two test classes.
    */
  private type FooBar = Foo | Bar
end FilterInstanceOfStageSpec

class FilterInstanceOfStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers:
  def this() = this(ActorSystem("FilterInstanceOfStageSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import FilterInstanceOfStageSpec.*

  "A FilterInstanceOfStage" should "filter incoming objects based on their type" in :
    val fooInput = List(Foo("test instance"), Foo("Another test instance"), Foo("more foo"), Foo("multi-foo"))
    val barInput = List(Bar(1), Bar(2), Bar(3), Bar(42))
    val input = fooInput.zip(barInput).flatMap(p => List(p._1, p._2))
    val source = Source(input)
    val sink = Sink.fold[List[Foo], Foo](Nil)((lst, e) => e :: lst)
    val filterStage = FilterInstanceOfStage[Foo]

    source.via(filterStage).runWith(sink) map : result =>
      result.reverse should contain theSameElementsInOrderAs fooInput
