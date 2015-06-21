import AssemblyKeys._
import DockerKeys._
import sbt.Keys._
import sbtdocker.mutable.Dockerfile
import sbtdocker.ImageName

name := "router"

organization := "systemzoo"

version := "1"

startYear := Some(2015)

/* scala versions and options */
scalaVersion := "2.11.2"

val akka = "2.3.3"
val spray = "1.3.2"

/* dependencies */
libraryDependencies ++= Seq (
  "com.typesafe.akka"           %% "akka-actor"               % akka
  // -- json --
  ,"io.spray"                   %%  "spray-json"              % "1.3.1"
  // -- testing --
  ,"org.scalatest"              %% "scalatest"                % "2.2.1"  % "test"
  ,"io.spray"                   %% "spray-testkit"            % spray    % "test"
  // -- Spray --
  ,"io.spray"                   %% "spray-routing"            % spray
  ,"io.spray"                   %% "spray-client"             % spray
  // -- Logging --
  ,"ch.qos.logback"              % "logback-classic"          % "1.1.2"
  ,"com.typesafe.scala-logging" %% "scala-logging-slf4j"      % "2.1.2"
  ,"com.typesafe"                % "config"                   % "1.2.1"
  // -- Caching --
  ,"com.google.guava"            % "guava"                    % "18.0"
  ,"com.google.code.findbugs"    % "jsr305"                   % "1.3.9"
  ,"com.typesafe.akka"          %% "akka-slf4j"               % "2.3.6"
  // -- decoding --
  ,"commons-codec"               % "commons-codec"            % "1.2"
 ).map(_.force())

/* avoid duplicate slf4j bindings */
libraryDependencies ~= { _.map(_.exclude("org.slf4j", "slf4j-jdk14")) }

resolvers ++= Seq(
  "spray repo" at "http://repo.spray.io",
  Resolver.sonatypeRepo("releases"),
  Resolver.sonatypeRepo("snapshots")
)

val testSettings = Seq(
  fork in Test := true,
  javaOptions in Test := Seq("-Denv=local")
)

testSettings

test in assembly := {}

net.virtualvoid.sbt.graph.Plugin.graphSettings

assemblySettings

mainClass in assembly := Some("com.systemzoo.Router")

dockerSettings

docker <<= (docker dependsOn assembly)

dockerfile in docker := {
  val artifact = (outputPath in assembly).value
  val artifactTargetPath = s"/app/${artifact.name}"
  new Dockerfile {
    from("develar/java")
    add(artifact, artifactTargetPath)
    entryPoint("java", "-Denv=prod", "-jar", artifactTargetPath)
    expose(80)
  }
}

imageName in docker := {
  ImageName(
    namespace = Some(organization.value),
    repository = name.value,
    tag = Some("latest")
  )
}

Seq(Revolver.settings: _*)
