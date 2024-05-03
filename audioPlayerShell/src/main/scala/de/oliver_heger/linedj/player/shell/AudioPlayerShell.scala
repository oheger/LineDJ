/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.player.shell

import de.oliver_heger.linedj.player.engine.mp3.Mp3AudioStreamFactory
import de.oliver_heger.linedj.player.engine.{AudioStreamFactory, CompositeAudioStreamFactory, DefaultAudioStreamFactory}
import de.oliver_heger.linedj.player.engine.stream.PausePlaybackStage.PlaybackState
import de.oliver_heger.linedj.player.engine.stream.{AudioEncodingStage, LineWriterStage, PausePlaybackStage}
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.stream.scaladsl.{FileIO, Keep, Sink}
import org.apache.pekko.stream.{KillSwitch, KillSwitches}

import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable.IndexedSeq
import scala.concurrent.ExecutionContext
import scala.io.StdIn.readLine
import scala.util.{Failure, Success}

/**
  * An object implementing a simple shell for issuing commands to create and
  * manipulate audio streams.
  */
object AudioPlayerShell:
  def main(args: Array[String]): Unit =
    println("Audio Player Shell")

    implicit val actorSystem: classic.ActorSystem = classic.ActorSystem("AudioPlayerShell")
    val audioStreamFactory = new CompositeAudioStreamFactory(List(Mp3AudioStreamFactory, DefaultAudioStreamFactory))
    val streamHandler = new AudioStreamHandler(audioStreamFactory)
    var done = false

    while !done do
      prompt()
      val (command, arguments) = readLine().split("""\s(?=([^"]*"[^"]*")*[^"]*$)""").splitAt(1)

      command.head.toLowerCase(Locale.ROOT) match
        case "exit" =>
          checkArgumentsAndRun(command, arguments, 0, 0) { _ => done = true }

        case "play" =>
          checkArgumentsAndRun(command, arguments, 1, 1) { args =>
            streamHandler.newAudioStream(args.head)
          }

        case "start" =>
          streamHandler.startPlayback()

        case "stop" =>
          streamHandler.stopPlayback()

        case "" => // ignore empty input

        case _ =>
          println(s"Unknown command '${command.head}'.")

    println("Shutting down shell...")
    streamHandler.cancelCurrentStream()
    streamHandler.shutdown()
    actorSystem.terminate()

  /**
    * Checks whether a correct number of arguments was passed for a command. If
    * so, executes the function to run the command; otherwise, an error message
    * is printed.
    *
    * @param command   the array with the command name
    * @param arguments the array with the arguments
    * @param minArgs   the minimum number of arguments
    * @param maxArgs   the maximum number of arguments
    * @param run       the function to run the command
    */
  private def checkArgumentsAndRun(command: Array[String],
                                   arguments: Array[String],
                                   minArgs: Int,
                                   maxArgs: Int)
                                  (run: IndexedSeq[String] => Unit): Unit =
    if arguments.length < minArgs || arguments.length > maxArgs then
      val expectMsg = if minArgs == maxArgs then
        minArgs match
          case 0 => "no arguments"
          case 1 => "a single argument"
          case _ => s"exactly $minArgs arguments"
      else s"at least $minArgs and at most $maxArgs arguments"
      println(s"Command '${command.head}' expects $expectMsg, but got ${arguments.length}.")
    else
      run(arguments.toIndexedSeq)
end AudioPlayerShell

/**
  * Prints the prompt for user input.
  */
private def prompt(): Unit =
  print("$ ")

/**
  * Prints a message and shows a new prompt.
  *
  * @param msg the message to be printed
  */
private def printAndPrompt(msg: String): Unit =
  println(msg)
  prompt()

/**
  * A helper class that manages a currently played audio stream. It can create
  * a new stream, cancel the current one, and pause or resume playback.
  *
  * @param audioStreamFactory the [[AudioStreamFactory]]
  * @param system             the implicit actor system
  */
private class AudioStreamHandler(audioStreamFactory: AudioStreamFactory)
                                (implicit val system: classic.ActorSystem):
  /** Stores the kill switch to cancel the current audio stream. */
  private val refCancelStream = new AtomicReference[KillSwitch]

  private val pauseActor = system.spawn(PausePlaybackStage.pausePlaybackActor(PlaybackState.PlaybackPossible),
    "pauseActor")

  /**
    * Starts a new audio stream. If one is currently in progress, it is
    * canceled before.
    *
    * @param uri the URI to the audio stream to be played
    */
  def newAudioStream(uri: String): Unit =
    audioStreamFactory.audioStreamCreatorFor(uri) match
      case Some(value) =>
        cancelCurrentStream()
        val encodingConfig = AudioEncodingStage.AudioEncodingStageConfig(streamCreator = value,
          streamFactoryLimit = 4096,
          encoderStreamLimit = 4096)
        val source = FileIO.fromPath(Paths.get(uri))
        val sink = Sink.ignore
        implicit val typedSystem: ActorSystem[Nothing] = system.toTyped
        implicit val executionContext: ExecutionContext = typedSystem.executionContext

        println(s"Starting audio stream for '$uri'.'")
        val (ks, futDone) = source.viaMat(KillSwitches.single)(Keep.right)
          .via(AudioEncodingStage(encodingConfig))
          .via(PausePlaybackStage.pausePlaybackStage(pauseActor))
          .via(LineWriterStage(LineWriterStage.DefaultLineCreatorFunc))
          .toMat(sink)(Keep.both)
          .run()
        refCancelStream.set(ks)

        futDone andThen {
          case Success(_) =>
            printAndPrompt(s"Audio stream for '$uri' was completed successfully.")
            refCancelStream.set(null)
          case Failure(exception) =>
            println(s"Failed to play audio stream for '$uri'.")
            exception.printStackTrace()
            refCancelStream.set(null)
            prompt()
        }

      case None =>
        printAndPrompt(s"No audio stream creator found for '$uri'.")

  /**
    * Cancels an audio stream that is currently played. If no audio stream is
    * played currently, this function has no effect.
    */
  def cancelCurrentStream(): Unit =
    Option(refCancelStream.get()) foreach :
      killSwitch =>
        println("Canceling current audio stream.")
        killSwitch.shutdown()
        refCancelStream.set(null)

  /**
    * Stops audio playback.
    */
  def stopPlayback(): Unit =
    pauseActor ! PausePlaybackStage.StopPlayback
    printAndPrompt("Audio playback stopped.")

  /**
    * Starts audio playback.
    */
  def startPlayback(): Unit =
    pauseActor ! PausePlaybackStage.StartPlayback
    printAndPrompt("Audio playback started.")

  /**
    * Shuts down this object and frees the resources in use.
    */
  def shutdown(): Unit =
    pauseActor ! PausePlaybackStage.Stop
end AudioStreamHandler
