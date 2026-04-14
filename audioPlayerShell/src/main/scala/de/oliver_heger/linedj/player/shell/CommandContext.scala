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

package de.oliver_heger.linedj.player.shell

import com.github.cloudfiles.core.http.HttpRequestSender
import de.oliver_heger.linedj.archive.server.cloud.CloudArchiveModel
import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.player.engine.mp3.Mp3AudioStreamFactory
import de.oliver_heger.linedj.player.engine.stream.{AudioStreamPlayerStage, BufferedPlaylistSource}
import de.oliver_heger.linedj.player.engine.{CompositeAudioStreamFactory, DefaultAudioStreamFactory}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import org.apache.pekko.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import org.apache.pekko.util.Timeout
import org.jline.terminal.Terminal

import java.nio.file.{Files, Path, Paths}
import java.util.Locale
import scala.collection.immutable.IndexedSeq
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}

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
final case class CommandInfo(minArgs: Int,
                             maxArgs: Int,
                             help: List[String],
                             run: CommandContext.CommandHandler)

/**
  * A data class storing information about the result of a command execution.
  * The command handler function yields an instance of this class. This is
  * then evaluated by the main command loop to decide how to proceed.
  *
  * @param output the output of the command
  * @param exit   a flag whether to exit the shell
  */
final case class CommandResult(output: Output.CommandOutput,
                               exit: Boolean = false)

object CommandContext extends ArchiveModel.ArchiveJsonSupport, CloudArchiveModel.CloudArchiveJsonSupport:
  /** The command line argument to define a buffer. */
  private val BufferDirArgument = "buffer-dir"

  /** The command line argument to define the size of buffer files. */
  private val BufferSizeArgument = "buffer-size"

  /** The command line argument to enable the buffer full sources mode. */
  private val BufferFullSourcesArgument = "buffer-full-sources"

  /** The command line argument to connect to a media archive. */
  private val ArchiveUrlArgument = "archive-url"

  /** The command line argument to configure timeouts for requests. */
  private val HttpTimeoutArgument = "http-timeout"

  /** The default size of a buffer file. */
  private val DefaultBufferFileSize = 8388608 // 8 MB

  /** The default timeout for HTTP requests. */
  private val DefaultHttpTimeout = Timeout(30.seconds)

  /** The prefix for command line arguments. */
  private val ArgumentPrefix = "--"

  /**
    * A map containing the supported command line arguments and help texts for
    * them.
    */
  private val SupportedArguments = Map(
    BufferDirArgument -> ("Defines the directory where to create buffer files.\n" +
      "If present, buffering is enabled."),
    BufferSizeArgument -> "The optional size of a buffer file (in bytes).",
    BufferFullSourcesArgument -> ("A flag (true or false) that controls whether audio sources are fully\n" +
      "loaded to the buffer."),
    ArchiveUrlArgument -> ("Defines the URL of a media archive.\n" +
      "If present, additional commands to interact with this archive are available."),
    HttpTimeoutArgument -> ("Allows configuring a timeout (in seconds) for HTTP requests.\n" +
      "If undefined, a default timeout of " + DefaultHttpTimeout + " is used.")
  )

  /**
    * Type alias for a function that handles the execution of a command.
    *
    * @param ctx  the command context
    * @param args the arguments passed to this command
    * @return an object with the result of the command execution
    */
  type CommandHandler = (ctx: CommandContext, args: IndexedSeq[String]) => CommandResult

  /**
    * Creates the [[CommandContext]] for this shell. This contains the
    * supported commands and all required helper objects. This function also
    * parses the command line arguments. If this fails due to invalid
    * arguments, it throws an [[IllegalArgumentException]] exception.
    *
    * @param terminal the terminal
    * @param args     the command line arguments
    * @return the [[CommandContext]]
    */
  def create(terminal: Terminal, args: Array[String]): CommandContext =
    val argsMap = parseCommandLine(args)

    given actorSystem: classic.ActorSystem = classic.ActorSystem("AudioPlayerShell")

    given Timeout = argsMap.get(HttpTimeoutArgument).map(t => Timeout(t.toInt.seconds)).getOrElse(DefaultHttpTimeout)

    val basicCommands = createBasicCommands()
    val (archiveCommands, optArchiveActor) = createArchiveCommands(argsMap, actorSystem)

    val audioStreamFactory = new CompositeAudioStreamFactory(List(new Mp3AudioStreamFactory, DefaultAudioStreamFactory))
    val streamHandler = new PlaylistStreamHandler(audioStreamFactory, createBufferConfigFunc(argsMap), optArchiveActor)

    CommandContext(terminal, actorSystem, streamHandler, optArchiveActor, basicCommands ++ archiveCommands)

  /**
    * Prints help information for this application. Lists the supported command
    * line arguments.
    */
  def printHelp(): Unit =
    println("AudioPlayerShell [arguments]")
    println()
    println("Arguments have the form `--<argumentKey=argumentValue>`.")
    println("The following argument keys are supported:")
    println()
    SupportedArguments.toList.sortBy(_._1).foreach: (key, help) =>
      println(s"$key:")
      val helpLines = help.split('\n')
      helpLines.foreach(line => println("    " + line))

  /**
    * Creates the commands that are always available in the shell.
    *
    * @return a map with information about the basic commands
    */
  private def createBasicCommands(): Map[String, CommandInfo] = Map(
    "close" -> CommandInfo(
      minArgs = 0,
      maxArgs = 0,
      help = List(
        "Closes the playlist.",
        "Afterward, no more songs can be added. The existing playlist is fully played."
      ),
      run = (ctx, _) =>
        ctx.streamHandler.closePlaylist()
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
        "Enqueues one or multiple audio file(s) as denoted by <path> to the playlist. ",
        "If <path> points to a single file, this file is added. For directories, the content ",
        "is scanned recursively and added. If the path contains whitespace, it must be ",
        "surrounded by quotes."
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

  /**
    * Creates commands to interact with a media archive based on the provided
    * arguments. If the arguments contain the URL of a media archive, this
    * function creates an actor to send requests to this archive and commands
    * that use it to load data from the archive.
    *
    * @param args        the map with command line arguments
    * @param actorSystem the actor system
    * @param timeout     the timeout for HTTP requests
    * @return a tuple with archive commands and an optional HTTP actor
    *         reference
    */
  private def createArchiveCommands(args: Map[String, String],
                                    actorSystem: classic.ActorSystem)
                                   (using timeout: Timeout):
  (Map[String, CommandInfo], Option[ActorRef[HttpRequestSender.HttpCommand]]) =
    args.get(ArchiveUrlArgument) match
      case Some(url) =>
        given ActorSystem[_] = actorSystem.toTyped

        val httpActor = actorSystem.spawn(HttpRequestSender(Uri(url)), "archiveActor")
        (createArchiveCommands(httpActor), Some(httpActor))
      case None =>
        (Map.empty, None)

  /**
    * Creates commands to interact with a media archive that can be reached
    * using the given HTTP actor.
    *
    * @param httpActor the actor to send requests to the archive
    * @return a map with the corresponding commands
    */
  private def createArchiveCommands(httpActor: ActorRef[HttpRequestSender.HttpCommand])
                                   (using system: ActorSystem[_],
                                    timeout: Timeout): Map[String, CommandInfo] =
    Map(
      "credentials" -> CommandInfo(
        minArgs = 0,
        maxArgs = 0,
        help = List("Lists the keys of credentials that can be set to unlock archives."),
        run = (_, _) =>
          handleArchiveCommand[CloudArchiveModel.CredentialsInfo](
            httpActor,
            "/api/archive/credentials",
            "credentials"
          ): credentialsInfo =>
            val fileCredentials = if credentialsInfo.fileCredentials.isEmpty then List.empty
            else
              "Credentials files:" :: credentialsInfo.fileCredentials.toList.sorted
            val archiveCredentials = if credentialsInfo.archiveCredentials.isEmpty then List.empty
            else
              "Archive credentials:" :: credentialsInfo.archiveCredentials.toList.sorted
            val credentialCount = credentialsInfo.fileCredentials.size + credentialsInfo.archiveCredentials.size
            val header = s"Found $credentialCount pending credential(s)."
            val separator = if fileCredentials.isEmpty || archiveCredentials.isEmpty then List.empty[String]
            else List("")
            header :: fileCredentials ::: separator ::: archiveCredentials
      ),
      "list-media" -> CommandInfo(
        minArgs = 0,
        maxArgs = 0,
        help = List("Lists information about the media contained in the media archive."),
        run = (_, _) =>
          handleArchiveCommand[ArchiveModel.MediaOverview](httpActor, "/api/archive/media", "list-media"): media =>
            media.media.sortBy(_.title.toLowerCase(Locale.ROOT)).map: overview =>
              s"${overview.id.checksum}: \"${overview.title}\""
      ),
      "list-artists" -> CommandInfo(
        minArgs = 1,
        maxArgs = 1,
        help = List(
          "Lists information about the artists contained on a specific medium.",
          "Usage: list-artists <mediumID>"
        ),
        run = (_, args) =>
          val mediumID = args.head
          val requestUri = s"/api/archive/media/$mediumID/artists"
          handleArchiveCommand[ArchiveModel.ItemsResult[ArchiveModel.ArtistInfo]](
            httpActor,
            requestUri,
            "list-artists"
          ): artistsResult =>
            artistsResult.items.map: artist =>
              s"${artist.id}: ${artist.artistName}"
      ),
      "list-albums" -> CommandInfo(
        minArgs = 1,
        maxArgs = 2,
        help = List(
          "Lists information about the albums contained on a specific medium.",
          "Usage: list-albums <mediumID> [<artistID>]",
          "    If an artist ID is provided, only the albums of this artist are listed."
        ),
        run = (_, args) =>
          val mediumID = args.head
          val artistPath = if args.length == 2 then s"/artists/${args(1)}" else ""
          val requestUri = s"/api/archive/media/$mediumID$artistPath/albums"
          handleArchiveCommand[ArchiveModel.ItemsResult[ArchiveModel.AlbumInfo]](
            httpActor,
            requestUri,
            "list-albums"
          ): albumsResult =>
            albumsResult.items.map: album =>
              s"${album.id}: ${album.albumName}"
      ),
      "list-songs" -> CommandInfo(
        minArgs = 2,
        maxArgs = 2,
        help = List(
          "Lists information about specific songs contained on a medium.",
          "Usage: list-songs <mediumID> <elementID>",
          "    where <elementID> can reference either an artist or an album."
        ),
        run = (_, args) =>
          val mediumID = args.head
          val elementID = args(1)
          val requestPath = if elementID.startsWith("alb") then "albums" else "artists"
          val requestUri = s"/api/archive/media/$mediumID/$requestPath/$elementID/songs"
          handleArchiveCommand[ArchiveModel.ItemsResult[MediaMetadata]](
            httpActor,
            requestUri,
            "list-songs"
          ): songsResult =>
            songsResult.items.flatMap(songToOutput)
      )
    )

  /**
    * Generic function to handle commands that query data from a media archive.
    * The function performs the following steps:
    *  - It sends a request with the given URI to the archive.
    *  - It deserializes the response to the target type.
    *  - It invokes the output generator function to transform the result to a
    *    list of strings to be output to the console.
    *
    * @param httpActor    the actor to send requests to the archive
    * @param uri          the URI to send to the archive
    * @param command      the name of the command to be handled
    * @param outputFunc   a function to generate the output
    * @param system       the actor system
    * @param timeout      the timeout for the request
    * @param unmarshaller the object to unmarshal the response
    * @tparam A the type of the result object
    * @return the result for this command
    */
  private def handleArchiveCommand[A](httpActor: ActorRef[HttpRequestSender.HttpCommand],
                                      uri: String,
                                      command: String)
                                     (outputFunc: A => List[String])
                                     (using system: ActorSystem[_],
                                      timeout: Timeout,
                                      unmarshaller: Unmarshaller[HttpResponse, A]): CommandResult =
    given ExecutionContext = system.executionContext

    val lines = for
      result <- HttpRequestSender.sendRequestSuccess(httpActor, HttpRequest(uri = uri))
      data <- Unmarshal(result.response).to[A]
    yield outputFunc(data)
    CommandResult(Output.AsyncOutput(command, lines))

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
    * Parses the command line arguments to a map.
    *
    * @param args the command line arguments
    * @return a map with the single arguments and their values
    */
  private def parseCommandLine(args: Array[String]): Map[String, String] =
    args.map: arg =>
      val kv = parseArgument(arg)
      if kv.length != 2 || !SupportedArguments.contains(kv(0)) then
        throw new IllegalArgumentException(s"Invalid command line argument: '$arg'.")
      (kv(0), kv(1))
    .toMap

  /**
    * Parses a single command line argument into its key and value component.
    *
    * @param arg the argument
    * @return an array with ideally two arguments for the key and the value
    */
  private def parseArgument(arg: String): Array[String] =
    if arg.startsWith(ArgumentPrefix) then
      val components = arg.split("=")
      components(0) = components(0).substring(ArgumentPrefix.length)
      components
    else
      Array(arg)

  /**
    * Returns a configuration for a buffered source if such a source is
    * configured by command line arguments.
    *
    * @param argsMap            the map with command line arguments
    * @param streamPlayerConfig the config for the stream player
    * @return an optional config for a buffered source
    */
  private def createBufferConfigFunc(argsMap: Map[String, String])
                                    (streamPlayerConfig:
                                     AudioStreamPlayerStage.AudioStreamPlayerConfig[String, Any]):
  PlaylistStreamHandler.OptBufferedSourceConfig =
    argsMap.get(BufferDirArgument).map: bufferDir =>
      BufferedPlaylistSource.BufferedPlaylistSourceConfig(
        streamPlayerConfig = streamPlayerConfig,
        bufferFolder = Paths.get(bufferDir),
        bufferFileSize = argsMap.get(BufferSizeArgument).map(_.toInt).getOrElse(DefaultBufferFileSize),
        bufferFullSources = argsMap.get(BufferFullSourcesArgument).exists(_.toBoolean)
      )

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

  /**
    * Produces output lines for a song.
    *
    * @param song the song data
    * @return the output lines representing this song
    */
  private def songToOutput(song: MediaMetadata): List[String] =
    List(
      s"${song.checksum}:${optOut(song.artist)}",
      s"${optOut(song.trackNumber, prefix = "", suffix = " -")}${optOut(song.album, suffix = " -")}" +
        s"${optOut(song.title, prefix = " \"", suffix = "\"")}",
      ""
    )

  /**
    * Generates output for an [[Option]]. If the option is undefined, this
    * results in an empty string. Otherwise, result is the given prefix
    * followed by the string representation of the option value followed by the
    * given suffix.
    *
    * @param opt    the option to output
    * @param prefix a prefix to prepend to the option value
    * @param suffix a suffix to append to the option value
    * @tparam A the type of the option
    * @return the output string for this option value
    */
  private def optOut[A](opt: Option[A], prefix: String = " ", suffix: String = ""): String =
    opt.fold("")(prefix + _.toString + suffix)
end CommandContext

/**
  * A data class collecting all relevant information for the main command
  * loop of this application. When executing a command, the command gets
  * access to an instance of this class.
  *
  * @param terminal        the object to generate output
  * @param actorSystem     the actor system
  * @param streamHandler   the handler for audio streams
  * @param optArchiveActor optional reference to an actor for sending HTTP
  *                        requests to a configured media archive
  *
  * @param commands        the map with supported commands
  */
final case class CommandContext(terminal: Terminal,
                                actorSystem: classic.ActorSystem,
                                streamHandler: PlaylistStreamHandler,
                                optArchiveActor: Option[ActorRef[HttpRequestSender.HttpCommand]],
                                commands: Map[String, CommandInfo]):
  /**
    * Shuts down this context by releasing all resources in use.
    */
  def shutdown(): Unit =
    streamHandler.shutdown()
    optArchiveActor.foreach(_ ! HttpRequestSender.Stop)
    Await.ready(actorSystem.terminate(), 30.seconds)
end CommandContext
