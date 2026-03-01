ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.8.1"

val circeVersion = "0.14.15"
lazy val root = (project in file("."))
  .settings(
    name := "KnnHomeAutomations",
    scalacOptions ++= Seq("-Ykind-projector:underscores", "-rewrite", "-source 3.0-migration", "-source:future", "-language:experimental.modularity", "-Wunused:imports"),
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    libraryDependencies ++= Seq(
      "org.pf4j" % "pf4j" % "3.12.0",
      "org.typelevel" %% "cats-effect" % "3.5.4",
      "org.typelevel" %% "log4cats-slf4j" % "2.7.1",
      "net.sigusr" %% "examples" % "1.0.1",
    ),
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser"
    ).map(_ % circeVersion),


    // Fat JAR name
    assembly / assemblyJarName := s"${name.value}-${version.value}.jar",

    // Ensure Scala stdlib is included in the fat jar
    assembly / assemblyOption := (assembly / assemblyOption).value.withIncludeScala(true),

    // Common merge strategy to avoid META-INF conflicts
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs@_*) =>
        xs.map(_.toLowerCase) match {
          case ("manifest.mf" :: Nil) => MergeStrategy.discard
          case ("index.list" :: Nil) => MergeStrategy.discard
          case ("dependencies" :: Nil) => MergeStrategy.discard
          case ("license" :: Nil) => MergeStrategy.discard
          case ("notice" :: Nil) => MergeStrategy.discard
          case _ => MergeStrategy.discard
        }
      case _ => MergeStrategy.first
    }

  )
