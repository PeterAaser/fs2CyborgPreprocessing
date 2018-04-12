scalaVersion := "2.11.8"

libraryDependencies += "org.typelevel" %% "cats-core" % "1.0.1"
libraryDependencies += "org.typelevel" %% "cats-effect" % "0.8"
libraryDependencies += "co.fs2" %% "fs2-core" % "0.10.1"
libraryDependencies += "co.fs2" %% "fs2-io" % "0.10.1"

// Scalasignal Runtime library dependencies
libraryDependencies ++= Seq(
  "org.scalanlp"                %% "breeze" % "0.13.2",
  "org.scalanlp" %% "breeze-natives" % "0.13.2",
  "net.sourceforge.jtransforms" %  "jtransforms" % "2.4.0"
)