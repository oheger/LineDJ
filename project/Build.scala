/*
 * Copyright 2015-2016 The Developers Team.
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
  lazy val AkkaVersion = "2.4.4"
  lazy val OsgiVersion = "5.0.0"

  /** The copyright dates. */
  val CopyRight = "2015-2016"

  lazy val akkaDependencies = Seq(
    "com.typesafe.akka" %% "akka-actor" % AkkaVersion,
    "com.typesafe.akka" %% "akka-testkit" % AkkaVersion,
    "com.typesafe.akka" %% "akka-remote" % AkkaVersion,
    "com.typesafe.akka" %% "akka-stream" % AkkaVersion
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

  lazy val logDependencies = Seq(
    "org.slf4j" % "slf4j-api" % "1.7.10",
    "org.slf4j" % "slf4j-simple" % "1.7.10" % "test"
  )

  val defaultSettings = Seq(
    version := "1.0-SNAPSHOT",
    scalaVersion := "2.11.7",
    libraryDependencies ++= akkaDependencies,
    libraryDependencies ++= testDependencies,
    libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "1.0.3",
    HeaderPlugin.autoImport.headers := Map(
      "scala" -> Apache2_0(CopyRight, "The Developers Team."),
      "conf" -> Apache2_0(CopyRight, "The Developers Team.", "#")
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
    ) aggregate(shared, server, actorSystem, client, mediaBrowser, playlistEditor, reorderMedium,
      reorderRandomSongs, reorderAlbum, reorderArtist, playerEngine, radioPlayer)

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
      libraryDependencies ++= logDependencies
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

  /**
    * Project for the media browser client application. This application allows
    * browsing through the media stored in the music library.
    */
  lazy val mediaBrowser = Project(id = "browser",
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

  /**
    * Project for the playlist editor client application. This application
    * allows creating a playlist from the media stored in the library.
    */
  lazy val playlistEditor = Project(id = "pleditor", base = file("pleditor"))
    .enablePlugins(SbtOsgi)
    .settings(defaultSettings: _*)
    .settings(osgiSettings: _*)
    .settings(
      name := "linedj-pleditor",
      resolvers += Resolver.mavenLocal,
      libraryDependencies ++= jguiraffeDependencies,
      libraryDependencies ++= osgiDependencies,
      libraryDependencies ++= logDependencies,
      OsgiKeys.privatePackage := Seq(
        "de.oliver_heger.linedj.pleditor.ui.*"
      ),
      OsgiKeys.exportPackage := Seq("de.oliver_heger.linedj.pleditor.spi"),
      OsgiKeys.additionalHeaders :=
        Map("Service-Component" -> "OSGI-INF/*.xml")
    ) dependsOn (shared % "compile->compile;test->test", client % "compile->compile;test->test")

  /**
    * Project for the playlist medium reorder component. This is an
    * implementation of ''PlaylistReorderer'' which orders playlist elements
    * based on their URI.
    */
  lazy val reorderMedium = Project(id = "reorderMedium", base = file("reorderMedium"))
    .enablePlugins(SbtOsgi)
    .settings(defaultSettings: _*)
    .settings(osgiSettings: _*)
    .settings(
      name := "linedj-reorder-medium",
      resolvers += Resolver.mavenLocal,
      OsgiKeys.privatePackage := Seq(
        "de.oliver_heger.linedj.reorder.medium.*"
      ),
      OsgiKeys.additionalHeaders :=
        Map("Service-Component" -> "OSGI-INF/*.xml")
    ) dependsOn playlistEditor

  /**
    * Project for the playlist album reorder component. This is an
    * implementation of ''PlaylistReorderer'' which orders playlist elements
    * based on an album ordering.
    */
  lazy val reorderAlbum = Project(id = "reorderAlbum", base = file("reorderAlbum"))
    .enablePlugins(SbtOsgi)
    .settings(defaultSettings: _*)
    .settings(osgiSettings: _*)
    .settings(
      name := "linedj-reorder-album",
      resolvers += Resolver.mavenLocal,
      OsgiKeys.privatePackage := Seq(
        "de.oliver_heger.linedj.reorder.album.*"
      ),
      OsgiKeys.additionalHeaders :=
        Map("Service-Component" -> "OSGI-INF/*.xml")
    ) dependsOn playlistEditor

  /**
    * Project for the playlist artist reorder component. This is an
    * implementation of ''PlaylistReorderer'' which orders playlist elements
    * based on an artist ordering.
    */
  lazy val reorderArtist = Project(id = "reorderArtist", base = file("reorderArtist"))
    .enablePlugins(SbtOsgi)
    .settings(defaultSettings: _*)
    .settings(osgiSettings: _*)
    .settings(
      name := "linedj-reorder-artist",
      resolvers += Resolver.mavenLocal,
      OsgiKeys.privatePackage := Seq(
        "de.oliver_heger.linedj.reorder.artist.*"
      ),
      OsgiKeys.additionalHeaders :=
        Map("Service-Component" -> "OSGI-INF/*.xml")
    ) dependsOn playlistEditor

  /**
    * Project for the playlist random songs reorder component. This is an
    * implementation of ''PlaylistReorderer'' which produces a random order of
    * playlist elements. (No properties are used to group songs; they are
    * simply reordered arbitrarily.)
    */
  lazy val reorderRandomSongs = Project(id = "reorderRandomSongs", base = file("reorderRandomSongs"))
    .enablePlugins(SbtOsgi)
    .settings(defaultSettings: _*)
    .settings(osgiSettings: _*)
    .settings(
      name := "linedj-reorder-random-songs",
      resolvers += Resolver.mavenLocal,
      OsgiKeys.privatePackage := Seq(
        "de.oliver_heger.linedj.reorder.random.*"
      ),
      OsgiKeys.additionalHeaders :=
        Map("Service-Component" -> "OSGI-INF/*.xml")
    ) dependsOn playlistEditor

  /**
    * Project for the playlist random artist reorder component. This is an
    * implementation of ''PlaylistReorderer'' which produces a random order of
    * the artists of the songs in the playlist. The songs of an artist are
    * sorted in album order.
    */
  lazy val reorderRandomArtists = Project(id = "reorderRandomArtists", base = file("reorderRandomArtists"))
    .enablePlugins(SbtOsgi)
    .settings(defaultSettings: _*)
    .settings(osgiSettings: _*)
    .settings(
      name := "linedj-reorder-random-artists",
      resolvers += Resolver.mavenLocal,
      OsgiKeys.privatePackage := Seq(
        "de.oliver_heger.linedj.reorder.randomartist.*"
      ),
      OsgiKeys.additionalHeaders :=
        Map("Service-Component" -> "OSGI-INF/*.xml")
    ) dependsOn playlistEditor

  /**
    * Project for the playlist random album reorder component. This is an
    * implementation of ''PlaylistReorderer'' which produces a random order of
    * all albums encountered in the playlist. The songs of an album are sorted
    * by their track number (and name). The artists are not taken into
    * account.
    */
  lazy val reorderRandomAlbums = Project(id = "reorderRandomAlbums", base = file("reorderRandomAlbums"))
    .enablePlugins(SbtOsgi)
    .settings(defaultSettings: _*)
    .settings(osgiSettings: _*)
    .settings(
      name := "linedj-reorder-random-albums",
      resolvers += Resolver.mavenLocal,
      OsgiKeys.privatePackage := Seq(
        "de.oliver_heger.linedj.reorder.randomalbum.*"
      ),
      OsgiKeys.additionalHeaders :=
        Map("Service-Component" -> "OSGI-INF/*.xml")
    ) dependsOn playlistEditor

  /**
    * Project for the player engine.
    */
  lazy val playerEngine = Project(id = "playerEngine", base = file("playerEngine"))
    .enablePlugins(SbtOsgi)
    .settings(defaultSettings: _*)
    .settings(osgiSettings: _*)
    .settings(
      name := "player-engine",
      resolvers += Resolver.mavenLocal,
      libraryDependencies ++= Seq(
        "javazoom" % "jl" % "1.0",
        "javazoom" % "tritonus_share" % "1.0",
        "javazoom" % "mp3spi" % "1.9.4"
      ),
      libraryDependencies ++= logDependencies,
      OsgiKeys.privatePackage := Seq(
        "de.oliver_heger.linedj.player.engine.impl.*"
      )
  ) dependsOn(shared % "compile->compile;test->test")

  /**
    * Project for the radio player. This project implements a UI for an
    * internet radio player.
    */
  lazy val radioPlayer = Project(id = "radioPlayer", base = file("radioPlayer"))
    .enablePlugins(SbtOsgi)
    .settings(defaultSettings: _*)
    .settings(osgiSettings: _*)
    .settings(
      name := "radio-player",
      resolvers += Resolver.mavenLocal,
      OsgiKeys.privatePackage := Seq(
        "de.oliver_heger.linedj.radio.*"
      )
    ) dependsOn(client % "compile->compile;test->test", playerEngine)
}
