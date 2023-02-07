ThisBuild / version := "1.0"

ThisBuild / scalaVersion := "2.12.15"

lazy val root = (project in file("."))
  .settings(
    name := "Cases"
  )

lazy val catsVersion = "2.9.0"
lazy val zioVersion = "2.0.6"
lazy val calibanVersion = "2.0.2"
lazy val doobieVersion = "1.0.0-RC1"

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-core" % catsVersion,
  "dev.zio" %% "zio" % zioVersion,
  "dev.zio" %% "zio-test" % zioVersion,
  "dev.zio" %% "zio-test-sbt" % zioVersion,
  "dev.zio" %% "zio-streams" % zioVersion,
  "dev.zio" %% "zio-test-junit" % zioVersion,
  "dev.zio" %% "zio-json" % "0.4.2",
  "dev.zio" %% "zio-config" % "3.0.7",
  "dev.zio" %% "zio-interop-cats" % "23.0.0.0",
  //"dev.zio" %% "zio-http" % "0.0.4"
  "io.d11" %% "zhttp" % "2.0.0-RC10",
  "com.github.ghostdogpr" %% "caliban" % calibanVersion,
  "com.github.ghostdogpr" %% "caliban-zio-http" % calibanVersion,
  "org.tpolecat" %% "doobie-core" % doobieVersion,
  "org.tpolecat" %% "doobie-postgres" % doobieVersion,
  "org.tpolecat" %% "doobie-hikari" % doobieVersion
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

scalacOptions ++= Seq(
  "-language:higherKinds"
)
