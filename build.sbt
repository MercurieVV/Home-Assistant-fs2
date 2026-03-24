ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val deploy = taskKey[Unit]("Deploy fat jar to Home Assistant addon")

ThisBuild / scalaVersion := "3.8.1"

val circeVersion = "0.14.15"

lazy val root = (project in file("."))
  .settings(
    name           := "KnnHomeAutomations",
    scalacOptions ++= Seq(
      "-Xkind-projector:underscores",
      "-source:future",
      "-language:experimental.modularity",
      "-Wunused:imports",
    ),
    semanticdbEnabled    := true,
    semanticdbVersion    := scalafixSemanticdb.revision,
    libraryDependencies ++= Seq(
      "org.pf4j"                        % "pf4j"                  % "3.12.0" % Provided,
      "org.typelevel"                  %% "cats-effect"           % "3.6.3",
      "org.typelevel"                  %% "log4cats-slf4j"        % "2.7.1",
      "ch.qos.logback"                  % "logback-classic"       % "1.5.18",
      "org.influxdb"                    % "influxdb-java"         % "2.25",
      "com.github.loki4j"               % "loki-logback-appender" % "1.5.2",
      "net.logstash.logback"            % "logstash-logback-encoder" % "8.0",
      "net.sigusr"                     %% "examples"              % "1.0.1",
      "io.github.mercurievv.minuscles" %% "tuples_transformers"   % "0.1.0",
      "io.github.mercurievv.minuscles" %% "opaques"               % "0.2.0",
    ),
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser",
    ).map(_ % circeVersion),
    libraryDependencies  += "io.circe"      %% "circe-optics"  % "0.15.0",
    libraryDependencies  += "dev.optics"    %% "monocle-macro" % "3.3.0",
    libraryDependencies  += "com.47deg"     %% "fetch"         % "3.1.2",
    libraryDependencies  += "org.typelevel" %% "kittens"       % "3.5.0",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "discipline-munit" % "2.0.0"      % Test,
      "org.typelevel" %% "cats-laws"        % "2.12.0"     % Test,
      "io.circe"      %% "circe-testing"    % circeVersion % Test,
    ),

    // Deploy to Home Assistant addon
    deploy := {
      import scala.sys.process._
      val jar = assembly.value
      val baseUrl = "http://192.168.0.247:8666/plugins"
      val deleteCode = Seq("curl", "-f", "-X", "DELETE", s"$baseUrl/knns-home-automations:1.0.0")
        .!(ProcessLogger(println, System.err.println))
      println(s"Deleted exit code: $deleteCode")
      val exitCode = Seq(
        "curl",
        "-f",
        "-F",
        s"file=@${jar.getAbsolutePath}",
        baseUrl,
      ).!(ProcessLogger(println, System.err.println))
      if (exitCode != 0) sys.error(s"Deploy failed with exit code $exitCode")
    },

    // Make Provided deps available when running locally via `sbt run`
    Runtime / unmanagedClasspath ++= update.value.select(configurationFilter("provided")).map(Attributed.blank),

    // Fat JAR name
    assembly / assemblyJarName := s"${name.value}-${version.value}.jar",

    // Ensure Scala stdlib is included in the fat jar
    assembly / assemblyOption := (assembly / assemblyOption).value.withIncludeScala(true),

    // Common merge strategy to avoid META-INF conflicts
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) =>
        xs.map(_.toLowerCase) match {
          case "manifest.mf" :: Nil  => MergeStrategy.discard
          case "index.list" :: Nil   => MergeStrategy.discard
          case "dependencies" :: Nil => MergeStrategy.discard
          case "license" :: Nil      => MergeStrategy.discard
          case "notice" :: Nil       => MergeStrategy.discard
          case "services" :: _       => MergeStrategy.concat
          case _                     => MergeStrategy.discard
        }
      case _ => MergeStrategy.first
    },
  )
