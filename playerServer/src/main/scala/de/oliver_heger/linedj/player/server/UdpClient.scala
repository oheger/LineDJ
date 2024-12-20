package de.oliver_heger.linedj.player.server

import java.awt.Desktop
import java.net.{DatagramPacket, DatagramSocket, InetAddress, URI}
import scala.util.{Failure, Success, Try, Using}

/**
  * A simple client application to discover the player server and open its
  * control UI in a browser.
  *
  * The application sends a discovery request to the player server, either
  * using a default configuration or overrides provided on the command line.
  * If a response is received, it opens a browser at this URL. Note that this
  * application is limited; for instance, there is no sophisticated error
  * handling or some kind of retry mechanism.
  */
object UdpClient:
  /**
    * A data class defining the lookup operation to be performed.
    *
    * @param multicastAddress the UDP multicast address
    * @param port             the port
    * @param command          the command to sent to the server
    */
  private case class LookupConfig(multicastAddress: String,
                                  port: Int,
                                  command: String)

  /** A default lookup configuration object. */
  private val DefaultLookupConfig = LookupConfig(PlayerServerConfig.DefaultLookupMulticastAddress,
    PlayerServerConfig.DefaultLookupPort,
    "playerServer?")

  def main(args: Array[String]): Unit =
    parseCommandLine(args) match
      case Failure(exception) =>
        invalidCommandLine(exception)

      case Success(lookupConfig) =>
        locateService(lookupConfig)

  /**
    * Handles an invalid command line.
    *
    * @param exception the exception thrown while parsing the command line
    */
  private def invalidCommandLine(exception: Throwable): Unit =
    println("Invalid command line options.")
    println(exception.getMessage)
    println()
    println("The following options are supported:")
    println("--address: The multicast UDP address to send the request to.")
    println("--port:    The port on which the service is listening.")
    println("--command: The command expected by the service.")
    System.exit(1)

  /**
    * Locates the service defined by the given [[LookupConfig]].
    *
    * @param lookupConfig the lookup configuration for the service
    */
  private def locateService(lookupConfig: LookupConfig): Unit =
    println("Trying to locate service using the following configuration:")
    println(s"Multicast address: ${lookupConfig.multicastAddress}")
    println(s"Port:              ${lookupConfig.port}")
    println(s"Command:           ${lookupConfig.command}")
    println()

    Using(new DatagramSocket()) { socket =>
      val data = lookupConfig.command.getBytes
      val packet = new DatagramPacket(data,
        data.length,
        InetAddress.getByName(lookupConfig.multicastAddress),
        lookupConfig.port)

      println("Sending request.")
      socket.send(packet)
      println("Request sent. Waiting for response.")

      val buf = new Array[Byte](256)
      val receivePacket = new DatagramPacket(buf, buf.length)
      socket.receive(receivePacket)
      val response = new String(receivePacket.getData, 0, receivePacket.getLength)

      println("Received response: " + response)

      Try(URI.create(response)) match
        case Failure(exception) =>
          println(s"This is not a valid URI.")
          System.exit(2)

        case Success(uri) =>
          if Desktop.isDesktopSupported then
            println("Opening browser at this URL.")
            Desktop.getDesktop.browse(uri)
    }

  /**
    * Tries to parse the command line. Here the service to be looked up can be
    * specified using corresponding command line options.
    *
    * @param args the array with command line arguments
    * @return a [[Try]] with the extracted [[LookupConfig]]
    */
  private def parseCommandLine(args: Array[String]): Try[LookupConfig] =
    args.foldLeft(Try(DefaultLookupConfig)) { (res, opt) =>
      res.flatMap { config =>
        parseOption(opt, config)
      }
    }

  /**
    * Tries to parse a single command line option. The option is expected to be
    * in the form `key=value` where `key` is one of the supported options.
    *
    * @param option the options to be parsed
    * @param config the current [[LookupConfig]]
    * @return a [[Try]] with the updated [[LookupConfig]]
    */
  private def parseOption(option: String, config: LookupConfig): Try[LookupConfig] =
    val separatorPos = option.indexOf('=')
    if separatorPos > 0 then
      option.substring(0, separatorPos) match
        case "--address" => Success(config.copy(multicastAddress = option.substring(separatorPos + 1)))
        case "--port" => Success(config.copy(port = option.substring(separatorPos + 1).toInt))
        case "--command" => Success(config.copy(command = option.substring(separatorPos + 1)))
        case k => Failure(new IllegalArgumentException(s"Unsupported command line option: '$k'."))
    else
      Failure(new IllegalArgumentException(s"Expected key/value pair as command line option: '$option'."))
