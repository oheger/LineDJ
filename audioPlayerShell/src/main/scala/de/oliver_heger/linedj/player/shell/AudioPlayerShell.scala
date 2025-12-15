/*
 * Copyright 2015-2025 The Developers Team.
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
import de.oliver_heger.linedj.player.engine.stream.{AudioStreamPlayerStage, BufferedPlaylistSource}
import de.oliver_heger.linedj.player.engine.{AudioStreamFactory, CompositeAudioStreamFactory, DefaultAudioStreamFactory}
import de.oliver_heger.linedj.player.shell.PlaylistStreamHandler.OptBufferedSourceConfig
import org.apache.pekko.actor as classic
import org.jline.reader.{LineReaderBuilder, PrintAboveWriter}
import org.jline.terminal.{Terminal, TerminalBuilder}
import org.jline.utils.AttributedString

import java.nio.file.{Files, Path, Paths}
import java.util.Locale
import scala.collection.immutable.IndexedSeq
import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Using}

/**
  * An object implementing a simple shell for issuing commands to create and
  * manipulate audio streams.
  */
object AudioPlayerShell:
  /** The command line argument to define a buffer. */
  private val BufferDirArgument = "--buffer-dir"

  /** The command line argument to define the size of buffer files. */
  private val BufferSizeArgument = "--buffer-size"

  /** The command line argument to enable the buffer full sources mode. */
  private val BufferFullSourcesArgument = "--buffer-full-sources"

  /** The default size of a buffer file. */
  private val DefaultBufferFileSize = 8388608 // 8 MB

  /**
    * A data class collecting information about a command supported by the
    * shell. This is used by the main execution loop to check the provided
    * arguments and trigger the execution.
    *
    * @param minArgs the minimum number of arguments of this command
    * @param maxArgs the maximum number of arguments of this command
    * @param help    the help texts (multi-line) for this command
    * @param run     the execution function for this command
    */
  private case class CommandInfo(minArgs: Int,
                                 maxArgs: Int,
                                 help: List[String],
                                 run: CommandHandler)

  /**
    * A data class collecting all relevant information for the main command
    * loop of this application. When executing a command, the command gets
    * access to an instance of this class.
    *
    * @param terminal      the object to generate output
    * @param actorSystem   the actor system
    * @param streamHandler the handler for audio streams
    * @param commands      the map with supported commands
    */
  private case class CommandContext(terminal: Terminal,
                                    actorSystem: classic.ActorSystem,
                                    streamHandler: PlaylistStreamHandler,
                                    commands: Map[String, CommandInfo]):
    /**
      * Shuts down this context by releasing all resources in use.
      */
    def shutdown(): Unit =
      streamHandler.shutdown()
      Await.ready(actorSystem.terminate(), 30.seconds)
  end CommandContext

  /**
    * A data class storing information about the result of a command execution.
    * The command handler function yields an instance of this class. This is
    * then evaluated by the main command loop to decide how to proceed.
    *
    * @param output the output of the command
    * @param exit   a flag whether to exit the shell
    */
  private case class CommandResult(output: Output.CommandOutput,
                                   exit: Boolean = false)

  /**
    * Type alias for a function that handles the execution of a command.
    *
    * @param ctx  the command context
    * @param args the arguments passed to this command
    * @return an object with the result of the command execution
    */
  private type CommandHandler = (ctx: CommandContext, args: IndexedSeq[String]) => CommandResult

  def main(args: Array[String]): Unit =
    val result = Using.Manager: use =>
      use(Output.initializeLogging())
      val terminal = use(TerminalBuilder.builder().system(true).build())
      val writer = terminal.writer()
      val lineReader = LineReaderBuilder.builder()
        .terminal(terminal)
        .build()
      val promptString = new AttributedString("playerShell> ", Output.StylePrompt)

      val commandContext = createCommandContext(terminal, args)
      Output.initializeOutput(commandContext.actorSystem, terminal, new PrintAboveWriter(lineReader))
      Output.output(
        Output.SyncOutput(
          List(
            "Audio Player Shell",
            "Type `help` for a list of available commands."
          )
        )
      )

      var done = false
      while !done do
        val line = lineReader.readLine(promptString.toAnsi)
        val (command, rawArguments) = line.split("""\s(?=([^"]*"[^"]*")*[^"]*$)""").splitAt(1)
        val arguments = rawArguments.map: v =>
          v.stripPrefix("\"").stripSuffix("\"")

        if command.head.nonEmpty then
          commandContext.commands.get(command.head.toLowerCase(Locale.ROOT)) match
            case Some(cmdInfo) =>
              done = checkArgumentsAndRun(
                commandContext,
                command,
                arguments,
                cmdInfo.minArgs,
                cmdInfo.maxArgs
              )(cmdInfo.run)

            case None =>
              writer.println(s"Unknown command '${command.head}'.")
        writer.flush()

    result match
      case Failure(exception) =>
        exception.printStackTrace()
      case Success(value) =>

  /**
    * Returns a configuration for a buffered source if such a source is
    * configured by command line arguments.
    *
    * @param args               the array with command line arguments
    * @param streamPlayerConfig the config for the stream player
    * @return an optional config for a buffered source
    */
  private def createBufferConfigFunc(args: Array[String])
                                              (streamPlayerConfig:
                                               AudioStreamPlayerStage.AudioStreamPlayerConfig[String, Any]):
  OptBufferedSourceConfig =
    val argsMap = args.map { arg =>
      val kv = arg.split('=')
      if kv.length != 2 then
        throw new IllegalArgumentException(s"Invalid command line argument: '$arg'.")
      (kv(0), kv(1))
    }.toMap

    argsMap.get(BufferDirArgument).map: bufferDir =>
      BufferedPlaylistSource.BufferedPlaylistSourceConfig(
        streamPlayerConfig = streamPlayerConfig,
        bufferFolder = Paths.get(bufferDir),
        bufferFileSize = argsMap.get(BufferSizeArgument).map(_.toInt).getOrElse(DefaultBufferFileSize),
        bufferFullSources = argsMap.get(BufferFullSourcesArgument).exists(_.toBoolean)
      )

  /**
    * Creates the [[CommandContext]] for this shell. This contains the 
    * supported commands and all required helper objects.
    *
    * @param args the command line arguments
    * @return the [[CommandContext]]
    */
  private def createCommandContext(terminal: Terminal, args: Array[String]): CommandContext =
    given actorSystem: classic.ActorSystem = classic.ActorSystem("AudioPlayerShell")

    val audioStreamFactory = new CompositeAudioStreamFactory(List(new Mp3AudioStreamFactory, DefaultAudioStreamFactory))
    val streamHandler = new PlaylistStreamHandler(audioStreamFactory, createBufferConfigFunc(args))

    val commands = Map(
      "close" -> CommandInfo(
        minArgs = 0,
        maxArgs = 0,
        help = List(
          "Closes the playlist.",
          "Afterward, no more songs can be added. The existing playlist is fully played."
        ),
        run = (_, _) =>
          streamHandler.closePlaylist()
          result("Playlist has been closed.")
      ),
      "exit" -> CommandInfo(
        minArgs = 0,
        maxArgs = 0,
        help = List("Stops this application."),
        run = (ctx, _) =>
          Output.output(Output.SyncOutput(List("Shutting down AudioPlayerShell.")))
          Output.shutdownOutput()
          ctx.shutdown()
          CommandResult(Output.SyncOutput(List.empty), exit = true)
      ),
      "help" -> CommandInfo(
        minArgs = 0,
        maxArgs = 1,
        help = List("Shows help about the commands supported by this application"),
        run = (ctx, args) =>
          val output = if args.isEmpty then
            val buffer = ListBuffer.empty[String]
            buffer += "Available commands:"
            buffer += ""
            buffer ++= ctx.commands.keys.toList.sorted
            buffer += ""
            buffer += "Type `help <command>` to get information about a specific command."
            buffer.toList
          else
            ctx.commands.get(args.head) match
              case Some(info) =>
                val buffer = ListBuffer.empty[String]
                buffer += s"Command `${args.head}`:"
                buffer ++= info.help
                buffer.toList
              case None =>
                List(
                  s"Unknown command `${args.head}`",
                  "Type `help` for a list of all supported commands."
                )
          result(output)
      ),
      "play" -> CommandInfo(
        minArgs = 1,
        maxArgs = 1,
        help = List(
          "play <path>",
          "Enqueues one or multiple audio file(s) as denoted by <path> to the playlist. If <path> points to a single " +
            "file, this file is added. For directories, the content is scanned recursively and added. " +
            "If the path contains whitespace, it must be surrounded by quotes."
        ),
        run = (ctx, args) => {
          val files = filesToAdd(args.head)
          files.foreach(ctx.streamHandler.addToPlaylist)
          val output = files.map(uri => s"Added '$uri' to current playlist.")
          result(output)
        }
      ),
      "skip" -> CommandInfo(
        minArgs = 0,
        maxArgs = 0,
        help = List("Skips the currently played audio source and continues with the next one (if any)."),
        run = (ctx, _) =>
          ctx.streamHandler.skipCurrentSource()
          result("Skipping playback of current audio source.")
      ),
      "start" -> CommandInfo(
        minArgs = 0,
        maxArgs = 0,
        help = List("Resumes playback if it is currently paused."),
        run = (ctx, _) =>
          ctx.streamHandler.startPlayback()
          result("Audio playback started.")
      ),
      "stop" -> CommandInfo(
        minArgs = 0,
        maxArgs = 0,
        help = List("Pauses playback."),
        run = (ctx, _) =>
          ctx.streamHandler.stopPlayback()
          result("Audio playback stopped.")
      )
    )

    CommandContext(terminal, actorSystem, streamHandler, commands)

  /**
    * Convenience function to create a [[CommandResult]] object with only a 
    * single output message.
    *
    * @param output the output message
    * @return the result object
    */
  private def result(output: String): CommandResult = CommandResult(Output.SyncOutput(List(output)))

  /**
    * Convenience function to create [[CommandResult]] object with a list of
    * lines to output.
    *
    * @param lines the single lines of output
    * @return the result object
    */
  private def result(lines: List[String]): CommandResult = CommandResult(Output.SyncOutput(lines))

  /**
    * Checks whether a correct number of arguments was passed for a command. If
    * so, executes the function to run the command; otherwise, an error message
    * is printed.
    *
    * @param context   the command context
    * @param command   the array with the command name
    * @param arguments the array with the arguments
    * @param minArgs   the minimum number of arguments
    * @param maxArgs   the maximum number of arguments
    * @param run       the function to run the command
    * @return a flag whether the loop should exit
    */
  private def checkArgumentsAndRun(context: CommandContext,
                                   command: Array[String],
                                   arguments: Array[String],
                                   minArgs: Int,
                                   maxArgs: Int)
                                  (run: CommandHandler): Boolean =
    if arguments.length < minArgs || arguments.length > maxArgs then
      val expectMsg = if minArgs == maxArgs then
        minArgs match
          case 0 => "no arguments"
          case 1 => "a single argument"
          case _ => s"exactly $minArgs arguments"
      else s"at least $minArgs and at most $maxArgs arguments"
      Output.output(
        Output.SyncOutput(
          List(s"Command '${command.head}' expects $expectMsg, but got ${arguments.length}."),
          Output.StyleError
        )
      )
      false
    else
      val result = run(context, arguments.toIndexedSeq)
      Output.output(result.output)
      result.exit

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

