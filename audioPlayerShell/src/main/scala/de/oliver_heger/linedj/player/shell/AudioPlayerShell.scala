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
import de.oliver_heger.linedj.player.engine.stream.{AudioStreamPlayerStage, BufferedPlaylistSource, LineWriterStage, PausePlaybackStage}
import de.oliver_heger.linedj.player.engine.{AudioStreamFactory, CompositeAudioStreamFactory, DefaultAudioStreamFactory}
import de.oliver_heger.linedj.player.shell.AudioPlayerShell.BufferFunc
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.stream.scaladsl.{FileIO, Sink, Source}
import org.apache.pekko.stream.{BoundedSourceQueue, KillSwitch, KillSwitches}

import java.nio.file.{Files, Path, Paths}
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
      "Enqueues one or multiple audio file(s) as denoted by <path> to the playlist. If <path> points to a single " +
        "file, this file is added. For directories, the content is scanned recursively and added. " +
        "If the path contains whitespace, it must be surrounded by quotes."
    ),
    "stop" -> List("Pauses playback."),
    "start" -> List("Resumes playback if it is currently paused."),
    "skip" -> List("Skips the currently played audio source and continues with the next one (if any).")
  )

  /** The command line argument to define a buffer. */
  private val BufferDirArgument = "--buffer-dir"

  /** The command line argument to define the size of buffer files. */
  private val BufferSizeArgument = "--buffer-size"

  /** The default size of a buffer file. */
  private val DefaultBufferFileSize = 8388608 // 8 MB

  /**
    * Type definition for a function that can create a configuration for a
    * buffered source.
    */
  type BufferFunc = AudioStreamPlayerStage.AudioStreamPlayerConfig[String, String] =>
    Option[BufferedPlaylistSource.BufferedPlaylistSourceConfig[String, String]]

  def main(args: Array[String]): Unit =
    println("Audio Player Shell")
    println("Type `help` for a list of available commands.")

    implicit val actorSystem: classic.ActorSystem = classic.ActorSystem("AudioPlayerShell")
    val audioStreamFactory = new CompositeAudioStreamFactory(List(Mp3AudioStreamFactory, DefaultAudioStreamFactory))
    val streamHandler = new PlaylistStreamHandler(audioStreamFactory, createBufferConfigFunc(args))
    var done = false

    while !done do
      prompt()
      val (command, rawArguments) = readLine().split("""\s(?=([^"]*"[^"]*")*[^"]*$)""").splitAt(1)
      val arguments = rawArguments.map { v =>
        v.stripPrefix("\"").stripSuffix("\"")
      }

      command.head.toLowerCase(Locale.ROOT) match
        case "exit" =>
          checkArgumentsAndRun(command, arguments, 0, 0) { _ => done = true }

        case "play" =>
          checkArgumentsAndRun(command, arguments, 1, 1) { args =>
            filesToAdd(args.head).foreach(streamHandler.addToPlaylist)
          }

        case "start" =>
          streamHandler.startPlayback()

        case "stop" =>
          streamHandler.stopPlayback()

        case "skip" =>
          streamHandler.skipCurrentSource()

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
    * Returns a configuration for a buffered source if such a source is
    * configured by command line arguments.
    *
    * @param args               the array with command line arguments
    * @param streamPlayerConfig the config for the stream player
    * @tparam SRC the type of sources
    * @tparam SNK the type of sinks
    * @return an optional config for a buffered source
    */
  private def createBufferConfigFunc[SRC, SNK](args: Array[String])
                                              (streamPlayerConfig:
                                               AudioStreamPlayerStage.AudioStreamPlayerConfig[SRC, SNK]):
  Option[BufferedPlaylistSource.BufferedPlaylistSourceConfig[SRC, SNK]] =
    val argsMap = args.map { arg =>
      val kv = arg.split('=')
      if kv.length != 2 then
        throw new IllegalArgumentException(s"Invalid command line argument: '$arg'.")
      (kv(0), kv(1))
    }.toMap

    argsMap.get(BufferDirArgument).map { bufferDir =>
      BufferedPlaylistSource.BufferedPlaylistSourceConfig(
        streamPlayerConfig = streamPlayerConfig,
        bufferFolder = Paths.get(bufferDir),
        bufferFileSize = argsMap.get(BufferSizeArgument).map(_.toInt).getOrElse(DefaultBufferFileSize)
      )
    }

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

  /**
    * Determines the audio files to be added to the playlist from the given
    * path. The function handles both single files and directories correctly.
    *
    * @param path the path
    * @return a list with the audio files to be added
    */
  private def filesToAdd(path: String): List[String] =
    val fsPath = Paths.get(path)
    if Files.isDirectory(fsPath) then scanDirectoryForFilesToAdd(fsPath)
    else List(path)

  /**
    * Scans the given directory for files to be added to the playlist.
    *
    * @param dir the directory
    * @return a set with the files contained in this directory
    */
  private def scanDirectoryForFilesToAdd(dir: Path): List[String] =
    import scala.jdk.StreamConverters.*
    Files.walk(dir)
      .toScala(List)
      .filter(path => !Files.isDirectory(path))
      .map(_.toString)
      .sorted
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
  * @param bufferFunc         the function to configure a buffered source
  * @param system             the implicit actor system
  */
private class PlaylistStreamHandler(audioStreamFactory: AudioStreamFactory,
                                    bufferFunc: BufferFunc)
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
    skipCurrentSource()
    playlistKillSwitch.shutdown()
    pauseActor ! PausePlaybackStage.Stop

  /**
    * Cancels a currently played audio source, so that playback continues with
    * the next one in the playlist. If no audio source is currently played,
    * this command has no effect.
    */
  def skipCurrentSource(): Unit =
    Option(refCancelStream.get()).foreach { ks =>
      printAndPrompt("Skipping playback of current audio source.")
      ks.shutdown()
    }

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
    val source = Source.queue[String](100)
    val sink = Sink.foreach[AudioStreamPlayerStage.PlaylistStreamResult[Any, String]] {
      case AudioStreamPlayerStage.AudioStreamEnd(audioSourcePath) =>
        refCancelStream.set(null)
        printAndPrompt(s"Audio stream for '$audioSourcePath' was completed successfully.")
      case AudioStreamPlayerStage.AudioStreamStart(audioSourcePath, killSwitch) =>
        refCancelStream.set(killSwitch)
        printAndPrompt(s"Starting playback of '$audioSourcePath'.")
    }

    bufferFunc(config).map { bufferConfig =>
      println("Creating a buffered source with configuration: " + bufferConfig)
      val bufferedSource = BufferedPlaylistSource(bufferConfig, source)
      val bufferedConfig = BufferedPlaylistSource.mapConfig(bufferConfig.streamPlayerConfig)
      AudioStreamPlayerStage.runPlaylistStream(bufferedConfig, bufferedSource, sink)._1
    }.getOrElse {
      AudioStreamPlayerStage.runPlaylistStream(config, source, sink)._1
    }

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
