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
import de.oliver_heger.linedj.player.engine.stream.PausePlaybackStage.PlaybackState
import de.oliver_heger.linedj.player.engine.stream.{AudioStreamPlayerStage, LineWriterStage, PausePlaybackStage}
import de.oliver_heger.linedj.player.engine.{AudioStreamFactory, CompositeAudioStreamFactory, DefaultAudioStreamFactory}
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.stream.scaladsl.{FileIO, Sink, Source}
import org.apache.pekko.stream.{BoundedSourceQueue, KillSwitch, KillSwitches}

import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable.IndexedSeq
import scala.concurrent.{ExecutionContext, Future}
import scala.io.StdIn.readLine

/**
  * An object implementing a simple shell for issuing commands to create and
  * manipulate audio streams.
  */
object AudioPlayerShell:
  /**
    * A map defining the names of the existing commands and help texts for
    * them. The help texts consist of multiple lines.
    */
  private val Commands = Map(
    "exit" -> List("Stops this application."),
    "play" -> List(
      "play <path>",
      "Enqueues an audio file as denoted by <path> to the playlist. The argument is interpreted as path to the " +
        "audio file. If the path contains whitespace, it must be surrounded by quotes."
    ),
    "stop" -> List("Pauses playback."),
    "start" -> List("Resumes playback if it is currently paused.")
  )

  def main(args: Array[String]): Unit =
    println("Audio Player Shell")
    println("Type `help` for a list of available commands.")

    implicit val actorSystem: classic.ActorSystem = classic.ActorSystem("AudioPlayerShell")
    val audioStreamFactory = new CompositeAudioStreamFactory(List(Mp3AudioStreamFactory, DefaultAudioStreamFactory))
    val streamHandler = new PlaylistStreamHandler(audioStreamFactory)
    var done = false

    while !done do
      prompt()
      val (command, arguments) = readLine().split("""\s(?=([^"]*"[^"]*")*[^"]*$)""").splitAt(1)

      command.head.toLowerCase(Locale.ROOT) match
        case "exit" =>
          checkArgumentsAndRun(command, arguments, 0, 0) { _ => done = true }

        case "play" =>
          checkArgumentsAndRun(command, arguments, 1, 1) { args =>
            streamHandler.addToPlaylist(args.head)
          }

        case "start" =>
          streamHandler.startPlayback()

        case "stop" =>
          streamHandler.stopPlayback()

        case "help" =>
          checkArgumentsAndRun(command, arguments, 0, 1) { args =>
            if args.isEmpty then
              println("Available commands:")
              println()
              Commands.keys.toList.sorted.foreach(println)
              println()
              println("Type `help <command>` to get information about a specific command.")
            else
              Commands.get(args.head) match
                case Some(help) =>
                  println(s"Command `${args.head}`:")
                  help.foreach(println)
                case None =>
                  println(s"Unknown command `${args.head}`")
                  println("Type `help` for a list of all supported commands.")
          }

        case "" => // ignore empty input

        case _ =>
          println(s"Unknown command '${command.head}'.")

    println("Shutting down shell...")
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
  * A helper class that manages the current playlist. It can add  a new source
  * to the playlist, and pause or resume playback.
  *
  * @param audioStreamFactory the [[AudioStreamFactory]]
  * @param system             the implicit actor system
  */
private class PlaylistStreamHandler(audioStreamFactory: AudioStreamFactory)
                                   (implicit val system: classic.ActorSystem):
  /** The execution context for operations with futures. */
  private given executionContext: ExecutionContext = system.dispatcher

  /** Stores the kill switch to cancel the current audio stream. */
  private val refCancelStream = new AtomicReference[KillSwitch]

  /** The actor to pause and resume playback. */
  private val pauseActor = system.spawn(PausePlaybackStage.pausePlaybackActor(PlaybackState.PlaybackPossible),
    "pauseActor")

  /** The kill switch for stopping the whole playlist. */
  private val playlistKillSwitch = KillSwitches.shared("stopPlaylist")

  /** The queue for adding new audio sources to the playlist. */
  private val playlistQueue = startPlaylistStream()

  /**
    * Starts a new audio stream. If one is currently in progress, it is
    * canceled before.
    *
    * @param uri the URI to the audio stream to be played
    */
  def addToPlaylist(uri: String): Unit =
    playlistQueue.offer(uri)
    printAndPrompt(s"Added '$uri' to current playlist.")

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
    * Shuts down this object and stops the playlist.
    */
  def shutdown(): Unit =
    playlistKillSwitch.shutdown()
    pauseActor ! PausePlaybackStage.Stop

  /**
    * Starts the stream for the playlist and returns the queue for adding new
    * audio sources to be played.
    *
    * @return the queue for adding elements to the playlist
    */
  private def startPlaylistStream(): BoundedSourceQueue[String] =
    val config = AudioStreamPlayerStage.AudioStreamPlayerConfig(
      sourceResolverFunc = resolveAudioSource,
      sinkProviderFunc = audioStreamSink,
      audioStreamFactory = audioStreamFactory,
      pauseActor = pauseActor,
      optKillSwitch = Some(playlistKillSwitch)
    )
    val source = Source.queue[String](10)
    val sink = Sink.foreach[String] { audioSourcePath =>
      printAndPrompt(s"Audio stream for '$audioSourcePath' was completed successfully.")
    }
    AudioStreamPlayerStage.runPlaylistStream(config, source, sink)._1

  /**
    * A function to resolve audio sources in the playlist. The string is 
    * interpreted as path to the audio file to be played.
    *
    * @param path the path to the audio file
    * @return a ''Future'' with the source to be played
    */
  private def resolveAudioSource(path: String): Future[AudioStreamPlayerStage.AudioStreamSource] =
    Future {
      AudioStreamPlayerStage.AudioStreamSource(path, FileIO.fromPath(Paths.get(path)))
    }

  /**
    * Returns the sink for the audio stream with the given path. The sink 
    * issues the path of the source when playback is done.
    *
    * @param audioSourcePath the path of the current audio source
    * @return the sink for playing a single audio stream
    */
  private def audioStreamSink(audioSourcePath: String): Sink[LineWriterStage.PlayedAudioChunk, Future[String]] =
    Sink.last[LineWriterStage.PlayedAudioChunk].mapMaterializedValue(_.map(_ => audioSourcePath))
end PlaylistStreamHandler
