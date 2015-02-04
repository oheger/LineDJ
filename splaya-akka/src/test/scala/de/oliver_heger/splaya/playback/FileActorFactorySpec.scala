package de.oliver_heger.splaya.playback

import akka.actor.{ActorContext, ActorRef, Props}
import de.oliver_heger.splaya.io.{FileReaderActor, FileWriterActor}
import org.mockito.Mockito.when
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
 * Test class for ''FileActorFactory''.
 */
class FileActorFactorySpec extends FlatSpec with Matchers with MockitoSugar {
  "A FileActorFactory" should "allow the creation of a file writer actor" in {
    val context = mock[ActorContext]
    val actorRef = mock[ActorRef]
    when(context.actorOf(Props[FileWriterActor])).thenReturn(actorRef)

    val factory = new FileActorFactory
    factory.createFileWriterActor(context) should be(actorRef)
  }

  it should "allow the creation of a file reader actor" in {
    val context = mock[ActorContext]
    val actorRef = mock[ActorRef]
    when(context.actorOf(Props[FileReaderActor])).thenReturn(actorRef)

    val factory = new FileActorFactory
    factory.createFileReaderActor(context) should be(actorRef)
  }
}
