package de.oliver_heger.splaya.playback

import akka.actor.{ActorContext, ActorRef, Props}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
 * Test class for ''LineWriterActorFactory''.
 */
class LineWriterActorFactorySpec extends FlatSpec with Matchers with MockitoSugar {
  "A LineWriterActorFactory" should "create a correct actor" in {
    val context = mock[ActorContext]
    val ref = mock[ActorRef]
    when(context.actorOf(Props[LineWriterActor])).thenReturn(ref)

    val factory = new LineWriterActorFactory
    factory.createLineWriterActor(context) should be(ref)
  }
}
