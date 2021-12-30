/*
 * Copyright 2015-2021 The Developers Team.
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

import com.github.oheger.sbt.spifly.SbtSpiFly
import com.github.oheger.sbt.spifly.SbtSpiFly.autoImport._
import OsgiImagePlugin.autoImport._
import com.typesafe.sbt.osgi.{OsgiKeys, SbtOsgi}

/** Definition of versions. */
lazy val AkkaVersion = "2.6.18"
lazy val AkkaHttpVersion = "10.2.7"
lazy val OsgiVersion = "5.0.0"
lazy val VersionScala = "2.13.7"
lazy val VersionScalaz = "7.3.5"
lazy val VersionJavaFX = "11.0.2"
lazy val VersionJguiraffe = "1.4"
lazy val VersionSlf4j = "1.7.32"
lazy val VersionScalaTest = "3.2.9"
lazy val VersionMockito = "1.9.5"
lazy val VersionScalaTestMockito = "1.0.0-M2"
lazy val VersionJunit = "4.13.2"  // needed by mockito
lazy val VersionJackson = "2.13.1"
lazy val VersionAeron = "1.37.0"
lazy val VersionSslConfig = "0.6.0"
lazy val VersionCloudFiles = "0.3"

ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature")

lazy val akkaDependencies = Seq(
  "com.typesafe.akka" %% "akka-actor" % AkkaVersion,
  "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-testkit" % AkkaVersion % Test,
  "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
  "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion,
  "com.typesafe.akka" %% "akka-remote" % AkkaVersion,
  "com.typesafe.akka" %% "akka-serialization-jackson" % AkkaVersion,
  "com.fasterxml.jackson.core" % "jackson-annotations" % VersionJackson,
  "org.scala-lang" % "scala-reflect" % VersionScala
)

/**
  * Additional dependencies to drag in all the bundles to enable remote access
  * to actors, including serialization.
  */
lazy val remotingDependencies = Seq(
  "com.typesafe" %% "ssl-config-core" % VersionSslConfig,
  "com.fasterxml.jackson.core" % "jackson-core" % VersionJackson,
  "com.fasterxml.jackson.core" % "jackson-databind" % VersionJackson,
  "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % VersionJackson,
  "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % VersionJackson,
  "com.fasterxml.jackson.module" % "jackson-module-parameter-names" % VersionJackson,
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % VersionJackson,
  "com.fasterxml.jackson.module" % "jackson-module-paranamer" % VersionJackson,
  "io.aeron" % "aeron-client" % VersionAeron,
  "io.aeron" % "aeron-driver" % VersionAeron
)

lazy val testDependencies = Seq(
  "org.scalatest" %% "scalatest" % VersionScalaTest % Test,
  "org.scalatestplus" %% "scalatestplus-mockito" % VersionScalaTestMockito % Test,
  "junit" % "junit" % VersionJunit % Test,
  "org.mockito" % "mockito-core" % VersionMockito % Test
)

lazy val osName = System.getProperty("os.name")
lazy val javaFxClassifier =
  if (osName.startsWith("Windows")) "windows"
  else if (osName.startsWith("Mac")) "mac"
  else "linux"
lazy val javaFxDependencies = Seq(
  "org.openjfx" % "javafx-controls" % VersionJavaFX classifier javaFxClassifier,
  "org.openjfx" % "javafx-base" % VersionJavaFX classifier javaFxClassifier,
  "org.openjfx" % "javafx-graphics" % VersionJavaFX classifier javaFxClassifier
)

lazy val jguiraffeDependencies = Seq(
  "net.sf.jguiraffe" % "jguiraffe-java-fx" % VersionJguiraffe,
  "net.sf.jguiraffe" % "jguiraffe" % VersionJguiraffe,
  "net.sf.jguiraffe" % "jguiraffe" % VersionJguiraffe % Test classifier "tests",
  "org.scala-lang.modules" %% "scala-parser-combinators" % "1.1.2"
) ++ javaFxDependencies

lazy val osgiDependencies = Seq(
  "org.osgi" % "org.osgi.core" % OsgiVersion % "provided",
  "org.osgi" % "org.osgi.compendium" % OsgiVersion % "provided"
)

lazy val logDependencies = Seq(
  "org.slf4j" % "slf4j-api" % VersionSlf4j,
  "org.slf4j" % "slf4j-simple" % VersionSlf4j % Test
)

val defaultSettings = Seq(
  version := "1.0-SNAPSHOT",
  scalaVersion := VersionScala,
  libraryDependencies ++= akkaDependencies,
  libraryDependencies ++= testDependencies,
  libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "2.0.1",
  resolvers += Resolver.mavenLocal
)

lazy val LineDJ = (project in file("."))
  .settings(defaultSettings: _*)
  .settings(
    name := "linedj-parent"
  ) aggregate(shared, archive, actorSystem, platform, mediaBrowser, playlistEditor,
  reorderMedium, reorderRandomSongs, reorderRandomArtists, reorderRandomAlbums,
  reorderAlbum, reorderArtist, playerEngine, radioPlayer,
  mp3PlaybackContextFactory, mediaIfcActors, mediaIfcRemote, mediaIfcEmbedded,
  mediaIfcDisabled, archiveStartup, archiveAdmin, appShutdownOneForAll, appWindowHiding,
  trayWindowList, archiveUnion, archiveLocalStartup, archiveCommon, archiveHttp,
  archiveHttpStartup, metaDataExtract, id3Extract, audioPlatform, persistentPlaylistHandler,
  audioPlayerUI, protocolWebDav, protocolOneDrive)

/**
  * A project with shared code which needs to be available on both client
  * and server.
  */
lazy val shared = (project in file("shared"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-shared",
    libraryDependencies += "org.scalaz" %% "scalaz-core" % VersionScalaz,
    OsgiKeys.exportPackage := Seq("de.oliver_heger.linedj.*"),
    OsgiKeys.privatePackage := Seq.empty
  )

/**
  * A project with traits and classes dealing with the generic extraction of
  * meta data from media files. There will be other projects that handle
  * specific kinds of meta data, such as ID3 tags.
  */
lazy val metaDataExtract = (project in file("mediaArchive/metaDataExtract"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-extract",
    OsgiKeys.exportPackage := Seq("de.oliver_heger.linedj.extract.metadata.*"),
    OsgiKeys.privatePackage := Seq.empty
  ) dependsOn (shared % "compile->compile;test->test")

/**
  * A project with classes that can extract meta data from mp3 audio files.
  * This functionality is required by multiple projects dealing with media
  * files; hence, it is made available as a separate project.
  */
lazy val id3Extract = (project in file("mediaArchive/id3Extract"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-archive-id3extract",
    OsgiKeys.exportPackage := Seq("de.oliver_heger.linedj.extract.id3.*"),
    OsgiKeys.privatePackage := Seq.empty
  ) dependsOn(shared % "compile->compile;test->test", metaDataExtract)

/**
  * An utility project providing common functionality needed by multiple
  * archive implementations. The project contains some actor implementations
  * and data model definitions.
  */
lazy val archiveCommon = (project in file("mediaArchive/archiveCommon"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-archive-common",
    libraryDependencies ++= logDependencies,
    libraryDependencies += "commons-configuration" % "commons-configuration" % "1.10",
    OsgiKeys.exportPackage := Seq("de.oliver_heger.linedj.archivecommon.*"),
    OsgiKeys.privatePackage := Seq.empty
  ) dependsOn (shared % "compile->compile;test->test")

/**
  * The media archive project. This contains code to scan a local folder
  * structure with media files and extract meta data about artists, albums,
  * and songs.
  */
lazy val archive = (project in file("mediaArchive/archive"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-archive",
    libraryDependencies ++= logDependencies,
    libraryDependencies += "commons-configuration" % "commons-configuration" % "1.10",
    OsgiKeys.exportPackage := Seq("de.oliver_heger.linedj.archive.*")
  ) dependsOn(shared % "compile->compile;test->test", archiveCommon,
  metaDataExtract, id3Extract)

/**
  * The media archive project. This contains code to manage the library with
  * artists, albums, and songs.
  */
lazy val archiveUnion = (project in file("mediaArchive/archiveUnion"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-archive-union",
    libraryDependencies ++= logDependencies,
    libraryDependencies += "commons-configuration" % "commons-configuration" % "1.10",
    OsgiKeys.exportPackage := Seq("de.oliver_heger.linedj.archiveunion.*"),
    OsgiKeys.privatePackage := Seq.empty
  ) dependsOn (shared % "compile->compile;test->test")

/**
  * The HTTP archive project. Via this project media files can be managed that
  * are stored on a remote host that can be accessed via HTTP requests.
  */
lazy val archiveHttp = (project in file("mediaArchive/archiveHttp"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-archive-http",
    libraryDependencies ++= logDependencies,
    libraryDependencies += "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
    libraryDependencies += "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
    libraryDependencies += "com.github.oheger" %% "cloud-files-core" % VersionCloudFiles,
    libraryDependencies += "com.github.oheger" %% "cloud-files-crypt" % VersionCloudFiles,
    libraryDependencies += "com.github.oheger" %% "cloud-files-cryptalg-aes" % VersionCloudFiles,
    libraryDependencies += "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion % Test,
    OsgiKeys.exportPackage := Seq("de.oliver_heger.linedj.archivehttp",
      "de.oliver_heger.linedj.archivehttp.config", "de.oliver_heger.linedj.archivehttp.temp",
      "de.oliver_heger.linedj.archivehttp.io.*", "de.oliver_heger.linedj.archivehttp.http"),
    OsgiKeys.privatePackage := Seq("de.oliver_heger.linedj.archivehttp.impl.*")
  ) dependsOn(shared % "compile->compile;test->test", archiveCommon, id3Extract)

/**
  * The WebDav protocol project. This is a module adding support for WebDav
  * servers as HTTP archives.
  */
lazy val protocolWebDav = (project in file("mediaArchive/protocolWebDav"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-protocol-webdav",
    libraryDependencies ++= logDependencies,
    libraryDependencies += "com.github.oheger" %% "cloud-files-webdav" % VersionCloudFiles,
    OsgiKeys.privatePackage := Seq("de.oliver_heger.linedj.archive.protocol.webdav.*"),
    OsgiKeys.importPackage := Seq("*"),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/webdavprotocol_component.xml")
  ) dependsOn(shared % "compile->compile;test->test", archiveHttpStartup)

/**
  * The OneDrive protocol project. This is a module adding support for OneDrive
  * servers as HTTP archives.
  */
lazy val protocolOneDrive = (project in file("mediaArchive/protocolOneDrive"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-protocol-onedrive",
    libraryDependencies ++= logDependencies,
    libraryDependencies += "com.github.oheger" %% "cloud-files-onedrive" % VersionCloudFiles,
    OsgiKeys.privatePackage := Seq("de.oliver_heger.linedj.archive.protocol.onedrive.*"),
    OsgiKeys.importPackage := Seq("*"),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/onedriveprotocol_component.xml")
  ) dependsOn(shared % "compile->compile;test->test", archiveHttpStartup)

/**
  * Project for the client platform. This project contains code shared by
  * all visual applications.
  */
lazy val platform = (project in file("platform"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-platform",
    libraryDependencies ++= jguiraffeDependencies,
    libraryDependencies ++= osgiDependencies,
    libraryDependencies ++= logDependencies,
    OsgiKeys.exportPackage := Seq("de.oliver_heger.linedj.platform.*"),
    OsgiKeys.privatePackage := Seq.empty,
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/managementapp_component.xml")
  ) dependsOn(shared % "compile->compile;test->test", actorSystem)

/**
  * A project providing the client-side actor system. This project uses the
  * akka OSGi-integration to setup an actor system and making it available as
  * OSGi service. It can then be used by all client applications.
  */
lazy val actorSystem = (project in file("actorSystem"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-actorSystem",
    libraryDependencies ++= osgiDependencies,
    libraryDependencies += "com.typesafe.akka" %% "akka-osgi" % AkkaVersion,
    // need to import packages of akka modules whose configuration has to be added
    OsgiKeys.importPackage := Seq(
      "akka.remote",
      "akka.stream",
      "akka.http;resolution:=optional",
      "akka.serialization.jackson",
      "*"),
    OsgiKeys.privatePackage := Seq("de.oliver_heger.linedj.actorsystem"),
    OsgiKeys.bundleActivator := Some("de.oliver_heger.linedj.actorsystem.Activator")
  ) dependsOn shared

/**
  * A project which is responsible for starting up the media archive in an
  * OSGi environment. (The archive project itself should be independent on
  * OSGi; therefore, there is a separate project for the startup of the
  * archive in OSGi.)
  */
lazy val archiveStartup = (project in file("mediaArchive/archiveStartup"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-archiveStartup",
    libraryDependencies ++= osgiDependencies,
    OsgiKeys.privatePackage := Seq("de.oliver_heger.linedj.archivestart.*"),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/*.xml")
  ) dependsOn(platform, archiveUnion)

/**
  * A project which is responsible for starting up the local media archive in
  * an OSGi environment. If this bundle is deployed in a LineDJ platform, a
  * local archive will be started which contributes its data to the configured
  * union archive.
  */
lazy val archiveLocalStartup = (project in file("mediaArchive/archiveLocalStartup"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-archiveLocalStartup",
    libraryDependencies ++= osgiDependencies,
    OsgiKeys.privatePackage := Seq("de.oliver_heger.linedj.archivelocalstart.*"),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/*.xml")
  ) dependsOn(platform % "compile->compile;test->test", archive, archiveAdmin)

/**
  * A project which is responsible for starting up an HTTP media archive in
  * an OSGi environment. If this bundle is deployed in a LineDJ platform,
  * components will be started which read the content from an HTTP archive and
  * contributes this data to the configured union archive.
  */
lazy val archiveHttpStartup = (project in file("mediaArchive/archiveHttpStartup"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-archiveHttpStartup",
    libraryDependencies ++= osgiDependencies,
    libraryDependencies ++= jguiraffeDependencies,
    libraryDependencies += "com.github.oheger" %% "cloud-files-crypt" % VersionCloudFiles,
    libraryDependencies += "com.github.oheger" %% "cloud-files-cryptalg-aes" % VersionCloudFiles,
    libraryDependencies += "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion % Test,
    OsgiKeys.privatePackage := Seq("de.oliver_heger.linedj.archivehttpstart.app.*"),
    OsgiKeys.exportPackage := Seq("de.oliver_heger.linedj.archivehttpstart.spi"),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/*.xml")
  ) dependsOn(platform % "compile->compile;test->test", archiveHttp)

/**
  * A project which implements an admin UI for the media archive.
  */
lazy val archiveAdmin = (project in file("mediaArchive/archiveAdmin"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-archiveAdmin",
    libraryDependencies ++= jguiraffeDependencies,
    libraryDependencies ++= osgiDependencies,
    OsgiKeys.privatePackage := Seq("de.oliver_heger.linedj.archiveadmin.*"),
    OsgiKeys.importPackage := Seq(
      "de.oliver_heger.linedj.platform.mediaifc.service",
      "*"),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/*.xml")
  ) dependsOn(platform % "compile->compile;test->test", archive)

/**
  * Project for the media browser client application. This application allows
  * browsing through the media stored in the music library.
  */
lazy val mediaBrowser = (project in file("browser"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-browser",
    libraryDependencies ++= jguiraffeDependencies,
    libraryDependencies ++= osgiDependencies,
    OsgiKeys.privatePackage := Seq(
      "de.oliver_heger.linedj.browser.*"
    ),
    OsgiKeys.importPackage := Seq(
      "de.oliver_heger.linedj.platform.bus",
      "de.oliver_heger.linedj.platform.mediaifc.config",
      "de.oliver_heger.linedj.platform.mediaifc.ext",
      "*"),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/browserapp_component.xml")
  ) dependsOn(shared % "compile->compile;test->test", platform % "compile->compile;test->test",
  audioPlatform)

/**
  * Project for the playlist editor client application. This application
  * allows creating a playlist from the media stored in the library.
  */
lazy val playlistEditor = (project in file("pleditor"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-pleditor",
    libraryDependencies ++= jguiraffeDependencies,
    libraryDependencies ++= osgiDependencies,
    libraryDependencies ++= logDependencies,
    OsgiKeys.privatePackage := Seq(
      "de.oliver_heger.linedj.pleditor.ui.*"
    ),
    OsgiKeys.exportPackage := Seq("de.oliver_heger.linedj.pleditor.spi"),
    OsgiKeys.importPackage := Seq(
      "de.oliver_heger.linedj.platform.bus",
      "de.oliver_heger.linedj.platform.mediaifc.ext",
      "de.oliver_heger.linedj.platform.audio.playlist.service",
      "*"),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/*.xml")
  ) dependsOn(shared % "compile->compile;test->test", platform % "compile->compile;test->test",
  audioPlatform)

/**
  * Project for the playlist medium reorder component. This is an
  * implementation of ''PlaylistReorderer'' which orders playlist elements
  * based on their URI.
  */
lazy val reorderMedium = (project in file("reorder/reorderMedium"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-reorder-medium",
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
lazy val reorderAlbum = (project in file("reorder/reorderAlbum"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-reorder-album",
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
lazy val reorderArtist = (project in file("reorder/reorderArtist"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-reorder-artist",
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
lazy val reorderRandomSongs = (project in file("reorder/reorderRandomSongs"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-reorder-random-songs",
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
lazy val reorderRandomArtists = (project in file("reorder/reorderRandomArtists"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-reorder-random-artists",
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
lazy val reorderRandomAlbums = (project in file("reorder/reorderRandomAlbums"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-reorder-random-albums",
    OsgiKeys.privatePackage := Seq(
      "de.oliver_heger.linedj.reorder.randomalbum.*"
    ),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/*.xml")
  ) dependsOn playlistEditor

/**
  * Project for the player engine.
  */
lazy val playerEngine = (project in file("playerEngine"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-player-engine",
    libraryDependencies ++= logDependencies,
    OsgiKeys.exportPackage := Seq(
      "!de.oliver_heger.linedj.player.engine.impl",
      "de.oliver_heger.linedj.player.engine.*"),
    OsgiKeys.privatePackage := Seq(
      "de.oliver_heger.linedj.player.engine.impl.*")
  ) dependsOn (shared % "compile->compile;test->test")

/**
  * Project for the mp3 playback context factory. This is a separate OSGi
  * bundle adding support for mp3 files to the player engine.
  */
lazy val mp3PlaybackContextFactory = (project in file("mp3PbCtxFactory"))
  .enablePlugins(SbtOsgi, SbtSpiFly)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(spiFlySettings: _*)
  .settings(
    name := "linedj-mp3-playback-context-factory",
    fork := true,
    libraryDependencies ++= Seq(
      "com.googlecode.soundlibs" % "jlayer" % "1.0.1-2",
      "com.googlecode.soundlibs" % "tritonus-share" % "0.3.7-3",
      "com.googlecode.soundlibs" % "mp3spi" % "1.9.5-2"
    ),
    libraryDependencies ++= logDependencies,
    OsgiKeys.privatePackage := Seq(
      "de.oliver_heger.linedj.player.engine.mp3.*"
    ),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/mp3PbCtxFactory_component.xml",
        "SPI-Consumer" -> "javax.sound.sampled.AudioSystem#getAudioInputStream")
  ) dependsOn playerEngine

/**
  * Project for the radio player. This project implements a UI for an
  * internet radio player.
  */
lazy val radioPlayer = (project in file("radioPlayer"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-radio-player",
    libraryDependencies ++= jguiraffeDependencies,
    libraryDependencies ++= osgiDependencies,
    OsgiKeys.privatePackage := Seq(
      "de.oliver_heger.linedj.radio.*"
    ),
    OsgiKeys.importPackage := Seq("de.oliver_heger.linedj.platform.bus", "*"),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/*.xml")
  ) dependsOn(platform % "compile->compile;test->test", audioPlatform)

/**
  * Project for the remote media interface. This project establishes a
  * connection to a media archive running on a remote host.
  */
lazy val mediaIfcActors = (project in file("mediaIfc/mediaIfcActors"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-actors-MediaIfc",
    OsgiKeys.exportPackage := Seq(
      "!de.oliver_heger.linedj.platform.mediaifc.actors.impl.*",
      "de.oliver_heger.linedj.platform.mediaifc.actors.*"
    ),
    OsgiKeys.privatePackage := Seq(
      "de.oliver_heger.linedj.platform.mediaifc.actors.impl.*"
    )
  ) dependsOn (platform % "compile->compile;test->test")

/**
  * Project for the remote media interface. This project establishes a
  * connection to a media archive running on a remote host.
  */
lazy val mediaIfcRemote = (project in file("mediaIfc/mediaIfcRemote"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-remote-MediaIfc",
    libraryDependencies ++= osgiDependencies,
    OsgiKeys.privatePackage := Seq(
      "de.oliver_heger.linedj.platform.mediaifc.remote.*"
    ),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/*.xml")
  ) dependsOn mediaIfcActors

/**
  * Project for the embedded media interface. This project accesses the
  * media archive running on the same virtual machine.
  */
lazy val mediaIfcEmbedded = (project in file("mediaIfc/mediaIfcEmbedded"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-embedded-MediaIfc",
    libraryDependencies ++= osgiDependencies,
    OsgiKeys.privatePackage := Seq(
      "de.oliver_heger.linedj.platform.mediaifc.embedded.*"
    ),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/*.xml")
  ) dependsOn mediaIfcActors

/**
  * Project for the disabled media interface. This project provides an empty
  * dummy implementation for the interface to the media archive. It can be
  * used for applications that do not require a media archive.
  */
lazy val mediaIfcDisabled = (project in file("mediaIfc/mediaIfcDisabled"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-disabled-MediaIfc",
    libraryDependencies ++= osgiDependencies,
    libraryDependencies ++= logDependencies,
    OsgiKeys.privatePackage := Seq(
      "de.oliver_heger.linedj.platform.mediaifc.disabled.*"
    ),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/*.xml")
  ) dependsOn (platform % "compile->compile;test->test")

/**
  * Project for the ''one for all'' shutdown handler. This project provides
  * shutdown handling that shuts down the platform when one of the
  * applications available is shutdown.
  */
lazy val appShutdownOneForAll = (project in file("appShutdownOneForAll"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-appMgr-shutdownOneForAll",
    libraryDependencies ++= osgiDependencies,
    OsgiKeys.privatePackage := Seq(
      "de.oliver_heger.linedj.platform.app.oneforall.*"
    ),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/*.xml")
  ) dependsOn (platform % "compile->compile;test->test")

/**
  * Project for the ''window hiding'' application manager. This project
  * contains an application manager implementation which keeps track on
  * the main windows of existing applications. When a window is closed it
  * is just hidden and can later be shown again. The platform can be
  * shutdown using an explicit command.
  */
lazy val appWindowHiding = (project in file("appWindowHiding"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-appMgr-windowHiding",
    libraryDependencies ++= osgiDependencies,
    libraryDependencies ++= logDependencies,
    OsgiKeys.exportPackage := Seq(
      "!de.oliver_heger.linedj.platform.app.hide.impl.*",
      "de.oliver_heger.linedj.platform.app.hide.*"
    ),
    OsgiKeys.privatePackage := Seq(
      "de.oliver_heger.linedj.platform.app.hide.impl.*"
    ),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/*.xml")
  ) dependsOn (platform % "compile->compile;test->test")

lazy val trayWindowList = (project in file("trayWindowList"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-trayWindowList",
    libraryDependencies ++= osgiDependencies,
    OsgiKeys.privatePackage := Seq(
      "de.oliver_heger.linedj.platform.app.tray.wndlist.*"
    ),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/*.xml")
  ) dependsOn(platform % "compile->compile;test->test", appWindowHiding)

/**
  * Project for the audio platform. This project provides basic services for
  * playing audio files.
  */
lazy val audioPlatform = (project in file("audioPlatform"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-audio-platform",
    libraryDependencies ++= osgiDependencies,
    OsgiKeys.exportPackage := Seq(
      "!de.oliver_heger.linedj.platform.audio.impl.*",
      "de.oliver_heger.linedj.platform.audio.*"
    ),
    OsgiKeys.privatePackage := Seq(
      "de.oliver_heger.linedj.platform.audio.impl.*"
    ),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/*.xml")
  ) dependsOn(platform % "compile->compile;test->test", playerEngine)

/**
  * Project for the persistent playlist handler. This module keeps track on
  * the current playlist by storing it in a file on disk. From there it can be
  * loaded and set again when the application is restarted.
  */
lazy val persistentPlaylistHandler = (project in file("persistentPLHandler"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-persistent-playlist-handler",
    libraryDependencies ++= osgiDependencies,
    OsgiKeys.privatePackage := Seq(
      "de.oliver_heger.linedj.playlist.persistence.*"
    ),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/*.xml")
  ) dependsOn(platform % "compile->compile;test->test", audioPlatform)

/**
  * Project for the audio player UI. This project implements a UI for an
  * audio player.
  */
lazy val audioPlayerUI = (project in file("audioPlayerUI"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(OSGi.osgiSettings)
  .settings(
    name := "linedj-audio-player-ui",
    libraryDependencies ++= jguiraffeDependencies,
    libraryDependencies ++= osgiDependencies,
    OsgiKeys.privatePackage := Seq(
      "de.oliver_heger.linedj.player.ui.*"
    ),
    OsgiKeys.importPackage := Seq(
      "de.oliver_heger.linedj.platform.bus",
      "de.oliver_heger.linedj.platform.mediaifc.ext",
      "de.oliver_heger.linedj.platform.audio.playlist.service",
      "*"),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/*.xml")
  ) dependsOn(platform % "compile->compile;test->test", audioPlatform)


/* Projects for OSGi applications/images. */

/**
  * A sequence with module definitions that should be excluded from all OSGi
  * images.
  */
lazy val DefaultExcludedModules = Seq(
  module(organization = "org.osgi")
)

/**
  * Project for the (local) archive application.
  *
  * This application starts a media archive populated from a local directory
  * together with an admin app.
  */
lazy val archiveOsgiImage = (project in file("images/archive"))
  .enablePlugins(OsgiImagePlugin)
  .settings(defaultSettings: _*)
  .settings(
    name := "linedj-archive-osgiImage",
    sourceImagePaths := Seq("base", "archive"),
    excludedModules := DefaultExcludedModules,
    libraryDependencies ++= remotingDependencies,
  ) dependsOn(archiveUnion, archiveStartup, archiveLocalStartup, archiveAdmin, appShutdownOneForAll,
  mediaIfcEmbedded)

/**
  * Project for the browser application.
  *
  * This application contains the media browser and the playlist editor 
  * applications. They can connect to a media archive.
  */
lazy val browserOsgiImage = (project in file("images/browser"))
  .enablePlugins(OsgiImagePlugin)
  .settings(defaultSettings: _*)
  .settings(
    name := "linedj-browser-osgiImage",
    sourceImagePaths := Seq("base", "baseAudioPlatform", "browser"),
    excludedModules := DefaultExcludedModules,
    libraryDependencies ++= remotingDependencies
  ) dependsOn(mediaBrowser, playlistEditor, reorderAlbum, reorderArtist, reorderMedium,
  reorderRandomAlbums, reorderRandomArtists, reorderRandomSongs, mediaIfcRemote, appWindowHiding,
  trayWindowList)


/**
  * Project for the (local) audio player application.
  *
  * This application provides an audio player that is connected to a media
  * archive.
  */
lazy val playerOsgiImage = (project in file("images/player"))
  .enablePlugins(OsgiImagePlugin)
  .settings(defaultSettings: _*)
  .settings(
    name := "linedj-player-osgiImage",
    sourceImagePaths := Seq("base", "baseAudioPlatform", "player"),
    excludedModules := DefaultExcludedModules,
    libraryDependencies ++= remotingDependencies
  ) dependsOn(mediaBrowser, playlistEditor, audioPlayerUI, reorderAlbum, reorderArtist, reorderMedium,
  reorderRandomAlbums, reorderRandomArtists, reorderRandomSongs, mediaIfcRemote, appWindowHiding,
  trayWindowList, persistentPlaylistHandler)

/**
  * Project for the advanced audio player application.
  *
  * This application is similar to the plain audio player, but it is connected 
  * to an HTTP archive.
  */
lazy val playerAdvancedOsgiImage = (project in file("images/player_advanced"))
  .enablePlugins(OsgiImagePlugin)
  .settings(defaultSettings: _*)
  .settings(
    name := "linedj-player-advanced-osgiImage",
    sourceImagePaths := Seq("base", "baseAudioPlatform", "player_advanced"),
    excludedModules := DefaultExcludedModules,
    libraryDependencies ++= remotingDependencies
  ) dependsOn(mediaBrowser, playlistEditor, audioPlayerUI, reorderAlbum, reorderArtist, reorderMedium,
  reorderRandomAlbums, reorderRandomArtists, reorderRandomSongs, appWindowHiding,
  trayWindowList, persistentPlaylistHandler, archiveUnion, archiveStartup, archiveHttp,
  archiveHttpStartup, mediaIfcEmbedded, protocolWebDav, protocolOneDrive)

/**
  * Project for the radio application.
  *
  * This application is an internet radio player.
  */
lazy val radioOsgiImage = (project in file("images/radio"))
  .enablePlugins(OsgiImagePlugin)
  .settings(defaultSettings: _*)
  .settings(
    name := "linedj-radio-osgiImage",
    sourceImagePaths := Seq("base", "baseAudioPlatform", "radio"),
    excludedModules := DefaultExcludedModules,
    libraryDependencies ++= remotingDependencies
  ) dependsOn(radioPlayer, appShutdownOneForAll, mediaIfcDisabled)
