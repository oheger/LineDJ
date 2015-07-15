lazy val root = (project in file(".")).
  settings(
    name := "splaya-akka",
    version := "1.1-SNAPSHOT",
    scalaVersion := "2.11.4",
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-xml" % "1.0.3",
      "com.typesafe.akka" %% "akka-actor" % "2.3.4",
      "com.typesafe.akka" %% "akka-testkit" % "2.3.4",
      "org.slf4j" % "slf4j-api" % "1.7.10",
      "org.scalatest" %% "scalatest" % "2.1.6" % "test",
      "junit" % "junit" % "4.12" % "test",
      "org.mockito" % "mockito-core" % "1.9.5" % "test",
      "org.slf4j" % "slf4j-simple" % "1.7.10" % "test"
    )
  )
