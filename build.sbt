import com.github.oheger.sbt.spifly.SbtSpiFly
import com.github.oheger.sbt.spifly.SbtSpiFly.autoImport._
import com.typesafe.sbt.osgi.SbtOsgi.autoImport._
import com.typesafe.sbt.osgi.{OsgiKeys, SbtOsgi}
import de.heikoseeberger.sbtheader.HeaderPlugin
import de.heikoseeberger.sbtheader.license.Apache2_0

/** Definition of versions. */
lazy val AkkaVersion = "2.4.17"
lazy val OsgiVersion = "5.0.0"
lazy val VersionScala = "2.11.8"

/** The copyright dates. */
val CopyRight = "2015-2017"

lazy val akkaDependencies = Seq(
  "com.typesafe.akka" %% "akka-actor" % AkkaVersion,
  "com.typesafe.akka" %% "akka-testkit" % AkkaVersion,
  "com.typesafe.akka" %% "akka-remote" % AkkaVersion,
  "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
  "org.scala-lang" % "scala-reflect" % VersionScala
)

lazy val testDependencies = Seq(
  "org.scalatest" %% "scalatest" % "2.1.6" % "test",
  "junit" % "junit" % "4.12" % "test",
  "org.mockito" % "mockito-core" % "1.9.5" % "test"
)

lazy val jguiraffeDependencies = Seq(
  "net.sf.jguiraffe" % "jguiraffe-java-fx" % "1.3.1" exclude
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
  scalaVersion := VersionScala,
  libraryDependencies ++= akkaDependencies,
  libraryDependencies ++= testDependencies,
  libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "1.0.3",
  HeaderPlugin.autoImport.headers := Map(
    "scala" -> Apache2_0(CopyRight, "The Developers Team."),
    "conf" -> Apache2_0(CopyRight, "The Developers Team.", "#")
  )
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
  trayWindowList, archiveUnion, archiveLocalStartup)

/**
  * A project with shared code which needs to be available on both client
  * and server.
  */
lazy val shared = (project in file("shared"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(osgiSettings: _*)
  .settings(
    name := "linedj-shared",
    OsgiKeys.exportPackage := Seq("de.oliver_heger.linedj.*")
  )

/**
  * The media archive project. This contains code to manage the library with
  * artists, albums, and songs.
  */
lazy val archive = (project in file("mediaArchive/archive"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(osgiSettings: _*)
  .settings(
    name := "linedj-archive",
    libraryDependencies ++= logDependencies,
    libraryDependencies += "commons-configuration" % "commons-configuration" % "1.10",
    OsgiKeys.exportPackage := Seq("de.oliver_heger.linedj.archive.*")
  ) dependsOn (shared % "compile->compile;test->test")

/**
  * The media archive project. This contains code to manage the library with
  * artists, albums, and songs.
  */
lazy val archiveUnion = (project in file("mediaArchive/archiveUnion"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(osgiSettings: _*)
  .settings(
    name := "linedj-archive-union",
    libraryDependencies ++= logDependencies,
    libraryDependencies += "commons-configuration" % "commons-configuration" % "1.10",
    OsgiKeys.exportPackage := Seq("de.oliver_heger.linedj.archiveunion.*"),
    OsgiKeys.privatePackage := Seq.empty
  ) dependsOn (shared % "compile->compile;test->test")

/**
  * Project for the client platform. This project contains code shared by
  * all visual applications.
  */
lazy val platform = (project in file("platform"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(osgiSettings: _*)
  .settings(
    name := "linedj-platform",
    resolvers += Resolver.mavenLocal,
    libraryDependencies ++= jguiraffeDependencies,
    libraryDependencies ++= osgiDependencies,
    libraryDependencies ++= logDependencies,
    OsgiKeys.exportPackage := Seq("de.oliver_heger.linedj.platform.*"),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/managementapp_component.xml")
  ) dependsOn (shared % "compile->compile;test->test")

/**
  * A project providing the client-side actor system. This project uses the
  * akka OSGi-integration to setup an actor system and making it available as
  * OSGi service. It can then be used by all client applications.
  */
lazy val actorSystem = (project in file("actorSystem"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(osgiSettings: _*)
  .settings(
    name := "linedj-actorSystem",
    libraryDependencies ++= osgiDependencies,
    libraryDependencies += "com.typesafe.akka" %% "akka-osgi" % AkkaVersion,
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
  .settings(osgiSettings: _*)
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
  .settings(osgiSettings: _*)
  .settings(
    name := "linedj-archiveLocalStartup",
    libraryDependencies ++= osgiDependencies,
    OsgiKeys.privatePackage := Seq("de.oliver_heger.linedj.archivelocalstart.*"),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/*.xml")
  ) dependsOn(platform, archive)

/**
  * A project which implements an admin UI for the media archive.
  */
lazy val archiveAdmin = (project in file("mediaArchive/archiveAdmin"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(osgiSettings: _*)
  .settings(
    name := "linedj-archiveAdmin",
    libraryDependencies ++= jguiraffeDependencies,
    libraryDependencies ++= osgiDependencies,
    OsgiKeys.privatePackage := Seq("de.oliver_heger.linedj.archiveadmin.*"),
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
  .settings(osgiSettings: _*)
  .settings(
    name := "linedj-browser",
    resolvers += Resolver.mavenLocal,
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
  ) dependsOn(shared % "compile->compile;test->test", platform % "compile->compile;test->test")

/**
  * Project for the playlist editor client application. This application
  * allows creating a playlist from the media stored in the library.
  */
lazy val playlistEditor = (project in file("pleditor"))
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
    OsgiKeys.importPackage := Seq(
      "de.oliver_heger.linedj.platform.bus",
      "de.oliver_heger.linedj.platform.mediaifc.ext",
      "*"),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/*.xml")
  ) dependsOn(shared % "compile->compile;test->test", platform % "compile->compile;test->test")

/**
  * Project for the playlist medium reorder component. This is an
  * implementation of ''PlaylistReorderer'' which orders playlist elements
  * based on their URI.
  */
lazy val reorderMedium = (project in file("reorderMedium"))
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
lazy val reorderAlbum = (project in file("reorderAlbum"))
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
lazy val reorderArtist = (project in file("reorderArtist"))
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
lazy val reorderRandomSongs = (project in file("reorderRandomSongs"))
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
lazy val reorderRandomArtists = (project in file("reorderRandomArtists"))
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
lazy val reorderRandomAlbums = (project in file("reorderRandomAlbums"))
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
lazy val playerEngine = (project in file("playerEngine"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(osgiSettings: _*)
  .settings(
    name := "player-engine",
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
  .settings(osgiSettings: _*)
  .settings(spiFlySettings: _*)
  .settings(
    name := "mp3-playback-context-factory",
    resolvers += Resolver.mavenLocal,
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
  .settings(osgiSettings: _*)
  .settings(
    name := "radio-player",
    resolvers += Resolver.mavenLocal,
    libraryDependencies ++= jguiraffeDependencies,
    libraryDependencies ++= osgiDependencies,
    OsgiKeys.privatePackage := Seq(
      "de.oliver_heger.linedj.radio.*"
    ),
    OsgiKeys.importPackage := Seq("de.oliver_heger.linedj.platform.bus", "*"),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/*.xml")
  ) dependsOn(platform % "compile->compile;test->test", playerEngine)

/**
  * Project for the remote media interface. This project establishes a
  * connection to a media archive running on a remote host.
  */
lazy val mediaIfcActors = (project in file("mediaIfcActors"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(osgiSettings: _*)
  .settings(
    name := "actors-MediaIfc",
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
lazy val mediaIfcRemote = (project in file("mediaIfcRemote"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(osgiSettings: _*)
  .settings(
    name := "remote-MediaIfc",
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
lazy val mediaIfcEmbedded = (project in file("mediaIfcEmbedded"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(osgiSettings: _*)
  .settings(
    name := "embedded-MediaIfc",
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
lazy val mediaIfcDisabled = (project in file("mediaIfcDisabled"))
  .enablePlugins(SbtOsgi)
  .settings(defaultSettings: _*)
  .settings(osgiSettings: _*)
  .settings(
    name := "disabled-MediaIfc",
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
  .settings(osgiSettings: _*)
  .settings(
    name := "appMgr-shutdownOneForAll",
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
  .settings(osgiSettings: _*)
  .settings(
    name := "appMgr-windowHiding",
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
  .settings(osgiSettings: _*)
  .settings(
    name := "trayWindowList",
    libraryDependencies ++= osgiDependencies,
    OsgiKeys.privatePackage := Seq(
      "de.oliver_heger.linedj.platform.app.tray.wndlist.*"
    ),
    OsgiKeys.additionalHeaders :=
      Map("Service-Component" -> "OSGI-INF/*.xml")
  ) dependsOn (platform % "compile->compile;test->test", appWindowHiding)
