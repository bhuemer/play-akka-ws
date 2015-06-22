name := "play-akka-ws"

version := "0.1"

scalaVersion := "2.11.6"

resolvers += "Typesafe Releases" at "https://repo.typesafe.com/typesafe/releases/"

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-ws" % "2.3.8",
  "com.typesafe.akka" %% "akka-stream-experimental" % "1.0-RC3",
  "com.typesafe.akka" %% "akka-http-experimental" % "1.0-RC3"
)