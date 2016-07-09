/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.facade

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.AskTimeoutException
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import de.oliver_heger.linedj.io.CloseRequest
import de.oliver_heger.linedj.player.engine.{PlayerConfig, RadioSource}
import de.oliver_heger.linedj.player.engine.impl.{EventManagerActor, LineWriterActor, PlaybackActor, RadioDataSourceActor}
import de.oliver_heger.linedj.player.engine.interval.IntervalQueries
import de.oliver_heger.linedj.player.engine.impl.schedule.RadioSchedulerActor
import de.oliver_heger.linedj.utils.{ChildActorFactory, SchedulerSupport}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.concurrent.Await

object RadioPlayerSpec {
  /** The name of the dispatcher for blocking actors. */
  private val BlockingDispatcherName = "TheBlockingDispatcher"
}

/**
  * Test class for ''RadioPlayer''.
  */
class RadioPlayerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike with
  BeforeAndAfterAll with Matchers {

  import RadioPlayerSpec._

  def this() = this(ActorSystem("RadioPlayerSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A RadioPlayer" should "support switching the radio source" in {
    val source = RadioSource("Radio Download")
    val helper = new RadioPlayerTestHelper

    helper.player switchToSource source
    helper.probeSchedulerActor.expectMsg(source)
  }

  it should "clear the buffer when playback starts" in {
    val helper = new RadioPlayerTestHelper

    helper.player.startPlayback()
    helper.probePlaybackActor.expectMsg(PlaybackActor.StartPlayback)
    helper.probeSourceActor.expectMsg(RadioDataSourceActor.ClearSourceBuffer)
  }

  it should "provide access to its current config" in {
    val helper = new RadioPlayerTestHelper

    helper.player.config should be(helper.config)
  }

  it should "correctly implement the close() method" in {
    val helper = new RadioPlayerTestHelper
    implicit val ec = system.dispatcher
    implicit val timeout = Timeout(100.milliseconds)

    intercept[AskTimeoutException] {
      Await.result(helper.player.close(), 1.second)
    }
    helper.probeSourceActor.expectMsg(CloseRequest)
    helper.probePlaybackActor.expectMsg(CloseRequest)
    helper.probeSchedulerActor.expectMsg(CloseRequest)
  }

  it should "support exclusions for radio sources" in {
    val helper = new RadioPlayerTestHelper
    val exclusions = Map(RadioSource("1") -> List(IntervalQueries.hours(1, 2)),
      RadioSource("2") -> List(IntervalQueries.hours(4, 5)))

    helper.player.initSourceExclusions(exclusions)
    helper.probeSchedulerActor.expectMsg(RadioSchedulerActor.RadioSourceData(exclusions))
  }

  it should "pass the event actor to the super class" in {
    val helper = new RadioPlayerTestHelper

    helper.player removeEventSink 20160709
    helper.probeEventActor.expectMsgType[EventManagerActor.RemoveSink]
  }

  /**
    * A helper class managing the dependencies of the test radio player
    * instance.
    */
  private class RadioPlayerTestHelper {
    /** Test probe for the playback actor. */
    val probePlaybackActor = TestProbe()

    /** Test probe for the radio data source actor. */
    val probeSourceActor = TestProbe()

    /** Test probe for the line writer actor. */
    val probeLineWriterActor = TestProbe()

    /** Test probe for the scheduler actor. */
    val probeSchedulerActor = TestProbe()

    /** Test probe for the event manager actor. */
    val probeEventActor = TestProbe()

    /** The test player configuration. */
    val config = createPlayerConfig()

    /** The player to be tested. */
    val player = RadioPlayer(config)

    /**
      * An actor creator function. This implementation checks the parameters
      * passed to the several actors and returns test probes.
      *
      * @param props the properties for the new actor
      * @param name  the actor name
      * @return an actor reference
      */
    private def actorCreatorFunc(props: Props, name: String): ActorRef = {
      name match {
        case "radioLineWriterActor" =>
          classOf[LineWriterActor] isAssignableFrom props.actorClass() shouldBe true
          props.dispatcher should be(BlockingDispatcherName)
          props.args should have size 0
          probeLineWriterActor.ref

        case "radioDataSourceActor" =>
          classOf[RadioDataSourceActor] isAssignableFrom props.actorClass() shouldBe true
          classOf[ChildActorFactory] isAssignableFrom props.actorClass() shouldBe true
          props.args should be(List(config, probeEventActor.ref))
          probeSourceActor.ref

        case "radioSchedulerActor" =>
          classOf[RadioSchedulerActor] isAssignableFrom props.actorClass() shouldBe true
          classOf[ChildActorFactory] isAssignableFrom props.actorClass() shouldBe true
          classOf[SchedulerSupport] isAssignableFrom props.actorClass() shouldBe true
          props.args should have length 1
          props.args.head should be(probeSourceActor.ref)
          probeSchedulerActor.ref

        case "radioEventManagerActor" =>
          props.actorClass() should be(classOf[EventManagerActor])
          props.args should have size 0
          probeEventActor.ref

        case "radioPlaybackActor" =>
          classOf[PlaybackActor] isAssignableFrom props.actorClass() shouldBe true
          props.args should contain theSameElementsAs List(config, probeSourceActor.ref,
            probeLineWriterActor.ref, probeEventActor.ref)
          probePlaybackActor.ref
      }
    }

    /**
      * Creates a test audio player configuration.
      *
      * @return the test configuration
      */
    private def createPlayerConfig(): PlayerConfig =
      PlayerConfig(mediaManagerActor = null, actorCreator = actorCreatorFunc,
        blockingDispatcherName = Some(BlockingDispatcherName))
  }

}
