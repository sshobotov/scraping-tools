name := """Ads Scrapper"""

version := "0.0.1"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "org.rogach" %% "scallop" % "2.0.5",
  "io.getquill" %% "quill-cassandra" % "0.10.0"
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

