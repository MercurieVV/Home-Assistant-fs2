ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.7.4"

lazy val root = (project in file("."))
  .settings(
    name := "KnnHomeAutomations",
    libraryDependencies ++= Seq(
      "org.pf4j"    % "pf4j"       % "3.12.0",
      "org.typelevel" %% "cats-effect" % "3.5.4"
    ),


    // Fat JAR name
    assembly / assemblyJarName := s"${name.value}-${version.value}.jar",

    // Ensure Scala stdlib is included in the fat jar
    assembly / assemblyOption := (assembly / assemblyOption).value.withIncludeScala(true),

    // Common merge strategy to avoid META-INF conflicts
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) =>
        xs.map(_.toLowerCase) match {
          case ("manifest.mf" :: Nil) => MergeStrategy.discard
          case ("index.list"  :: Nil) => MergeStrategy.discard
          case ("dependencies":: Nil) => MergeStrategy.discard
          case ("license"     :: Nil) => MergeStrategy.discard
          case ("notice"      :: Nil) => MergeStrategy.discard
          case _                      => MergeStrategy.discard
        }
      case _ => MergeStrategy.first
    }

  )
