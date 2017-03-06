name := """Ads Scrapper"""

version := "0.0.1"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "org.rogach" %% "scallop" % "2.1.1",
  "io.getquill" %% "quill-cassandra" % "1.1.0",
  "org.slf4j" % "slf4j-log4j12" %"1.7.21",
  "org.apache.commons" % "commons-io" % "1.3.2"
)

val atlassianAwsVersion = "7.0.0"

libraryDependencies ++= Seq(
  "io.atlassian.aws-scala" %% "aws-scala-core",
  "io.atlassian.aws-scala" %% "aws-scala-s3"
).map(_ % atlassianAwsVersion)

val circeVersion = "0.5.1"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % circeVersion)

assemblyMergeStrategy in assembly := {
  case path if path.endsWith(".properties") =>
    MergeStrategy.first
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

mainClass in (Compile, run) := Some("com.apptopia.labs.ads.cli.ApkAdsCredentials")
