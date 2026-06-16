import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}
import sbtcrossproject.JVMPlatform
import scalajscrossproject.JSPlatform

ThisBuild / scalaVersion := "3.9.0-RC1"
ThisBuild / version := "0.1.0-SNAPSHOT"

val jettyVersion = "12.1.10"
val laminarVersion = "18.0.0-M5"
val scalaJsDomVersion = "2.8.1"
val upickleVersion = "4.4.3"

lazy val root = project
  .in(file("."))
  .aggregate(commonJVM, commonJS, server, client)
  .settings(
    name := "quizly",
    publish / skip := true
  )

lazy val common = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("common"))
  .settings(
    name := "quizly-common",
    libraryDependencies += "com.lihaoyi" %%% "upickle" % upickleVersion
  )

lazy val commonJVM = common.jvm
lazy val commonJS = common.js

lazy val server = project
  .in(file("server"))
  .dependsOn(commonJVM)
  .settings(
    name := "quizly-server",
    libraryDependencies += "org.eclipse.jetty" % "jetty-server" % jettyVersion,
    assembly / mainClass := Some("quizly.server.QuizServer"),
    assembly / assemblyJarName := "quizly.jar",
    assembly / assemblyMergeStrategy := {
      case "module-info.class" => MergeStrategy.discard
      case path                => (ThisBuild / assemblyMergeStrategy).value(path)
    }
  )

lazy val client = project
  .in(file("client"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(commonJS)
  .settings(
    name := "quizly-client",
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "com.raquo" %%% "laminar" % laminarVersion,
      "org.scala-js" %%% "scalajs-dom" % scalaJsDomVersion
    )
  )
