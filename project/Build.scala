/*
 * Copyright 2015 The Developers Team.
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

import com.typesafe.sbt.osgi.{OsgiKeys, SbtOsgi}
import com.typesafe.sbt.osgi.SbtOsgi._
import sbt._
import Keys._
import de.heikoseeberger.sbtheader.HeaderPlugin
import de.heikoseeberger.sbtheader.license.Apache2_0

object Build extends Build {
  /** Definition of versions. */
  lazy val AkkaVersion = "2.3.12"
  lazy val OsgiVersion = "5.0.0"

  lazy val akkaDependencies = Seq(
    "com.typesafe.akka" %% "akka-actor" % AkkaVersion,
    "com.typesafe.akka" %% "akka-testkit" % AkkaVersion,
    "com.typesafe.akka" %% "akka-remote" % AkkaVersion
  )

  lazy val testDependencies = Seq(
    "org.scalatest" %% "scalatest" % "2.1.6" % "test",
    "junit" % "junit" % "4.12" % "test",
    "org.mockito" % "mockito-core" % "1.9.5" % "test"
  )

  lazy val jguiraffeDependencies = Seq(
    "net.sf.jguiraffe" % "jguiraffe-java-fx" % "1.4-SNAPSHOT" changing() exclude
      ("commons-discovery", "commons-discovery") exclude("jdom", "jdom"),
    "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.4"
  )

  lazy val osgiDependencies = Seq(
    "org.osgi" % "org.osgi.core" % OsgiVersion % "provided",
    "org.osgi" % "org.osgi.compendium" % OsgiVersion % "provided"
  )

  val defaultSettings = Seq(
    version := "1.0-SNAPSHOT",
    scalaVersion := "2.11.7",
    libraryDependencies ++= akkaDependencies,
    libraryDependencies ++= testDependencies,
    libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "1.0.3",
    HeaderPlugin.autoImport.headers := Map(
      "scala" -> Apache2_0("2015", "The Developers Team."),
      "conf" -> Apache2_0("2015", "The Developers Team.", "#")
    )
  )

  def osgiSettings = defaultOsgiSettings ++ Seq(
    packagedArtifact in (Compile, packageBin) <<= (artifact in (Compile, packageBin), OsgiKeys.bundle).identityMap
  )

  lazy val root = Project(id = "LineDJ",
    base = file("."))
    .settings(defaultSettings: _*)
    .settings(
      name := "linedj-parent"
    ) aggregate(shared, server, actorSystem, client, browser)

  /**
    * A project with shared code which needs to be available on both client
    * and server.
    */
  lazy val shared = Project(id = "shared",
    base = file("shared"))
    .enablePlugins(SbtOsgi)
    .settings(defaultSettings: _*)
    .settings(osgiSettings: _*)
    .settings(
      name := "linedj-shared",
      OsgiKeys.exportPackage := Seq("de.oliver_heger.linedj.*")
    )

  /**
    * The server project. This contains code to manage the library with
    * artists, albums, and songs.
    */
  lazy val server = Project(id = "server",
    base = file("server"))
    .settings(defaultSettings: _*)
    .settings(
      name := "linedj-server",
      libraryDependencies ++= Seq(
        "org.slf4j" % "slf4j-api" % "1.7.10",
        "org.slf4j" % "slf4j-simple" % "1.7.10" % "test"
      )
    ) dependsOn (shared % "compile->compile;test->test")

  /**
    * Project for the client platform. This project contains code shared by
    * all visual client applications.
    */
  lazy val client = Project(id = "client",
    base = file("client"))
    .enablePlugins(SbtOsgi)
    .settings(defaultSettings: _*)
    .settings(osgiSettings: _*)
    .settings(
      name := "linedj-client",
      resolvers += Resolver.mavenLocal,
      libraryDependencies ++= jguiraffeDependencies,
      libraryDependencies ++= osgiDependencies,
      OsgiKeys.exportPackage := Seq("de.oliver_heger.linedj.client.*"),
      OsgiKeys.additionalHeaders :=
        Map("Service-Component" -> "OSGI-INF/managementapp_component.xml")
    ) dependsOn (shared % "compile->compile;test->test")

  /**
    * A project providing the client-side actor system. This project uses the
    * akka OSGi-integration to setup an actor system and making it available as
    * OSGi service. It can then be used by all client applications.
    */
  lazy val actorSystem = Project(id = "actorSystem", base = file("actorSystem"))
    .enablePlugins(SbtOsgi)
    .settings(defaultSettings: _*)
    .settings(osgiSettings: _*)
    .settings(
      name := "linedj-actorSystem",
      libraryDependencies ++= osgiDependencies,
      libraryDependencies += "com.typesafe.akka" %% "akka-osgi" % AkkaVersion,
      OsgiKeys.privatePackage := Seq("de.oliver_heger.linedj.actorsystem"),
      OsgiKeys.bundleActivator := Some("de.oliver_heger.linedj.actorsystem.Activator")
    )

  lazy val browser = Project(id = "browser",
    base = file("browser"))
    .enablePlugins(SbtOsgi)
    .settings(defaultSettings: _*)
    .settings(osgiSettings: _*)
    .settings(
      name := "linedj-browser",
      resolvers += Resolver.mavenLocal,
      libraryDependencies ++= jguiraffeDependencies,
      libraryDependencies ++= osgiDependencies,
      OsgiKeys.privatePackage := Seq(
        "de.oliver_heger.linedj.browser.*"
      ),
      OsgiKeys.additionalHeaders :=
        Map("Service-Component" -> "OSGI-INF/browserapp_component.xml")
    ) dependsOn (shared % "compile->compile;test->test", client % "compile->compile;test->test")
}

