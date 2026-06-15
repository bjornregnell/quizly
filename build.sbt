scalaVersion := "3.9.0-RC1"

libraryDependencies += "org.eclipse.jetty" % "jetty-server" % "12.1.10"

assembly / mainClass := Some("quizly.QuizServer")
assembly / assemblyJarName := "quizly.jar"
ThisBuild / assemblyMergeStrategy := {
  case "module-info.class" => MergeStrategy.discard
  case path                => (ThisBuild / assemblyMergeStrategy).value(path)
}
