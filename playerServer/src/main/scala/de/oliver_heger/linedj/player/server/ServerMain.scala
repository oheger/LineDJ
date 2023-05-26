/*
 * Copyright 2015-2023 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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
package de.oliver_heger.linedj.player.server

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.server.Directives.*
import akka.io.Udp

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import scala.io.StdIn

/**
  * The main class of the Player Server.
  */
object ServerMain:
  final val MulticastAddress = "231.10.0.0"
  final val ControlPort = 4321
  private final val ServerPort = 8080

  def main(args: Array[String]): Unit =
    implicit val system: ActorSystem = ActorSystem("UDPServer")
    implicit val executionContext: ExecutionContext = system.dispatcher

    val route =
      pathSingleSlash {
        get {
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Player Server</h1>"))
        }
      }

    val bindingFuture = Http().newServerAt("0.0.0.0", ServerPort).bind(route)

    bindingFuture foreach { binding =>
      println(s"Server running on ${binding.localAddress}.")
      val localAddress = fetchActiveNetworkInterfaces()
        .map(_.localAddress.map(_.getAddress))
        .find(_.isDefined).flatten getOrElse binding.localAddress.getAddress
      val serverUrl = s"http://${localAddress.getHostAddress}:$ServerPort/"
      createListenerActor(system, serverUrl)
    }

    println("Press Enter to quit.")
    StdIn.readLine()

    println("Shutting down.")
    bindingFuture.flatMap(_.unbind())
      .onComplete(_ => system.terminate())

  private def createListenerActor(system: ActorSystem, serverUrl: String): ActorRef =
    system.actorOf(EndpointRequestHandlerActor.props(MulticastAddress, ControlPort, "playerServer?", serverUrl))
