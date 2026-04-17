ThisBuild / scalaVersion := "3.3.3"
ThisBuild / organization := "edu.uic.cs553"
ThisBuild / version      := "0.1.0"

lazy val akkaVersion = "2.8.5"

lazy val root = (project in file("."))
  .settings(
    name := "cs553-distributed-sim",
    libraryDependencies ++= Seq(
      // Akka Classic actors
      "com.typesafe.akka"  %% "akka-actor"          % akkaVersion,
      "com.typesafe.akka"  %% "akka-slf4j"           % akkaVersion,
      // Config
      "com.typesafe"        % "config"               % "1.4.3",
      // JSON parsing for NetGameSim graph loading
      "io.circe"           %% "circe-core"            % "0.14.6",
      "io.circe"           %% "circe-generic"         % "0.14.6",
      "io.circe"           %% "circe-parser"          % "0.14.6",
      // Logging
      "ch.qos.logback"      % "logback-classic"      % "1.4.14",
      // Test
      "org.scalatest"      %% "scalatest"             % "3.2.17"  % Test,
      "com.typesafe.akka"  %% "akka-testkit"          % akkaVersion % Test,
    ),
    scalacOptions ++= Seq(
      "-encoding", "UTF-8",
      "-deprecation",
      "-feature",
      "-unchecked"
    ),
    // Fork to avoid ActorSystem port conflicts between tests
    Test / fork := true,
    run  / fork := true,
  )
