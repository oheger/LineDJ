/*
 * Copyright 2015-2016 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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
package de.oliver_heger.linedj.archive.metadata

import java.nio.file.Paths

import akka.actor.{ActorRef, Props}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

object ProcessorActorMapSpec {
  /** Properties for creating a new actor. */
  private val CreationProps = Props(classOf[ID3FrameProcessorActor], null)

  /** Constant for a test path. */
  private val TestPath = Paths get "ProcessorActorMapSpec.mp3"

  /**
   * An answer class used to simulate the creation of specific child actors.
   * @param children the child actors to be returned by this answer
   */
  private class CreateChildActorAnswer(children: List[ActorRef]) extends Answer[ActorRef] {
    /** The list with child actors to be returned. */
    var currentChildActors = children

    override def answer(invocation: InvocationOnMock): ActorRef = {
      val result = currentChildActors.head
      currentChildActors = currentChildActors.tail
      result
    }
  }

}

/**
 * Test class for ''ProcessorActorMap''.
 */
class ProcessorActorMapSpec extends FlatSpec with Matchers with MockitoSugar {

  import ProcessorActorMapSpec._

  /**
   * Creates a mock child actor factory which returns the specified child
   * actors (in this order).
   * @param actors the child actors to be returned
   * @return the mock child factory
   */
  private def createChildFactory(actors: ActorRef*): ChildActorFactory = {
    val factory = mock[ChildActorFactory]
    when(factory.createChildActor(CreationProps)).then(new CreateChildActorAnswer(actors.toList))
    factory
  }

  /**
   * Creates a mock actor.
   * @return a mock actor
   */
  private def createActor(): ActorRef = mock[ActorRef]

  "A ProcessorActorMap" should "create a new child actor if needed" in {
    val actor = createActor()
    val factory = createChildFactory(actor)
    val map = new ProcessorActorMap(CreationProps)

    map.getOrCreateActorFor(TestPath, factory) should be(actor)
  }

  it should "return the same actor for the sam path" in {
    val actor = createActor()
    val factory = createChildFactory(actor)
    val map = new ProcessorActorMap(CreationProps)

    val result1 = map.getOrCreateActorFor(TestPath, factory)
    val result2 = map.getOrCreateActorFor(TestPath, factory)
    result1 should be(result2)
  }

  it should "return a different actor for another path" in {
    val actor1, actor2 = createActor()
    val factory = createChildFactory(actor1, actor2)
    val TestPath2 = Paths get "anotherPath.tst"
    val map = new ProcessorActorMap(CreationProps)

    map.getOrCreateActorFor(TestPath, factory) should be(actor1)
    val actorFor = map.getOrCreateActorFor(TestPath2, factory)
    actorFor should be(actor2)
  }

  it should "allow removing a child actor" in {
    val actor1, actor2 = createActor()
    val factory = createChildFactory(actor1, actor2)
    val map = new ProcessorActorMap(CreationProps)

    map.getOrCreateActorFor(TestPath, factory)
    map.removeItemFor(TestPath).get should be(actor1)
    map.getOrCreateActorFor(TestPath, factory) should be(actor2)
  }
}
