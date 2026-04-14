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

import org.jline.reader.{LineReaderBuilder, PrintAboveWriter}
import org.jline.terminal.TerminalBuilder
import org.jline.utils.AttributedString

import java.util.Locale
import scala.util.{Failure, Success, Using}

/**
  * An object implementing a simple shell for issuing commands to create and
  * manipulate audio streams.
  */
object AudioPlayerShell:
  def main(args: Array[String]): Unit =
    val result = Using.Manager: use =>
      use(Output.initializeLogging())
      val terminal = use(TerminalBuilder.builder().system(true).build())
      val writer = terminal.writer()
      val lineReader = LineReaderBuilder.builder()
        .terminal(terminal)
        .build()
      val promptString = new AttributedString("playerShell> ", Output.StylePrompt)

      val commandContext = CommandContext.create(terminal, lineReader, args)
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
        handleException(exception)
      case Success(value) =>

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
                                  (run: CommandContext.CommandHandler): Boolean =
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
    * Handles an exception that was thrown by the main function. In case of
    * invalid command line arguments, prints a help text.
    *
    * @param exception the exception thrown by the main function
    */
  private def handleException(exception: Throwable): Unit =
    exception match
      case iex: IllegalArgumentException =>
        println(iex.getMessage)
        println()
        CommandContext.printHelp()
      case e =>
        e.printStackTrace()
end AudioPlayerShell
