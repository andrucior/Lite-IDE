ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.7"
ThisBuild / organization := "com.liteide"

val Versions = new {
  val Http4s   = "0.23.30"
  val CatsEff  = "3.5.7"
  val Fs2      = "3.11.0"
  val Circe    = "0.14.10"
  val Logback  = "1.5.18"
  val Munit    = "1.1.0"
  val MunitCE3 = "2.0.0"
}

lazy val root = (project in file("."))
  .settings(
    name                := "lite-ide-backend",
    Compile / mainClass := Some("com.liteide.Main"),

    libraryDependencies ++= Seq(
      // Effect + streams
      "org.typelevel" %% "cats-effect" % Versions.CatsEff,
      "co.fs2"        %% "fs2-core"    % Versions.Fs2,

      // HTTP + WebSocket server
      "org.http4s"    %% "http4s-ember-server" % Versions.Http4s,
      "org.http4s"    %% "http4s-dsl"          % Versions.Http4s,
      "org.http4s"    %% "http4s-circe"        % Versions.Http4s,

      // JSON
      "io.circe"      %% "circe-core"    % Versions.Circe,
      "io.circe"      %% "circe-generic" % Versions.Circe,
      "io.circe"      %% "circe-parser"  % Versions.Circe,

      // Logging
      "ch.qos.logback" % "logback-classic" % Versions.Logback,

      // Tests
      "org.scalameta" %% "munit"             % Versions.Munit    % Test,
      "org.typelevel" %% "munit-cats-effect" % Versions.MunitCE3 % Test,
    ),

    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wunused:all",
      "-Wvalue-discard",
      "-Xfatal-warnings",
      "-source:3.3",
    ),

    Test / fork           := true,
    Compile / run / fork  := true,

    // Silence runtime warnings:
    //  - Cats Effect's "IOApp main is running on a thread other than the main thread"
    //    (unavoidable under sbt's forked `run`, which names its main thread differently).
    //  - JDK 24+ terminal-deprecation notice for `sun.misc.Unsafe::objectFieldOffset`
    //    emitted by scala3-library's LazyVals; harmless until a future JDK actually removes it.
    Compile / run / javaOptions ++= Seq(
      "-Dcats.effect.warnOnNonMainThreadDetected=false",
      "--sun-misc-unsafe-memory-access=allow",
    ),
  )
