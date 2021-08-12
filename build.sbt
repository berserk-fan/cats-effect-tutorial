name := "cats-effect-tutorial"

version := "3.2.1"

scalaVersion := "2.13.6"

libraryDependencies += "org.typelevel" %% "cats-effect" % "3.2.1" withSources() withJavadoc()

scalacOptions ++= Seq(
  "-Xsource:3",
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:postfixOps"
)
