package com.apptopia.labs.ads.cli

import java.io.{File, FileOutputStream}

import com.amazonaws.util.IOUtils
import com.typesafe.config.ConfigFactory
import io.atlassian.aws.s3._
import io.atlassian.aws.{AmazonClient, AmazonClientConnectionDef, Credential, AwsAction => Action}
import io.getquill.{CassandraAsyncContext, SnakeCase}

import com.apptopia.labs.ads.dao.{GooglePlayAppFiles, GooglePlaySdkGeneralData, SdkApps}

import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.sys.process._
import scalaz.{-\/, \/-}

object ApkAdsCredentials {

  lazy val tmpDir = System.getProperty("java.io.tmpdir")

  val adNetworks = Map(
    "admob"      -> ("Google AdMob", findAdMobCred _),
    "chartboost" -> ("Chartboost", findChartboostCred _)
  )

  def main(args: Array[String]): Unit = {
    assert(args.length > 0 && adNetworks.contains(args(0)),
      s"Provide valid ad network name, supported: ${adNetworks.keys.mkString(" ,")}")
    val (adNetwork, matchCred) = adNetworks(args(0))

    val config = ConfigFactory.load("local").withFallback(ConfigFactory.load())
    implicit val ctx = new CassandraAsyncContext[SnakeCase](config.getConfig("cassandra"))

    val toProcess =
      if (args.length > 1) findPackageS3Path(args(1)) map { _.toList }
      else retrieveAvailableS3Paths(adNetwork)

    val processed = toProcess flatMap {
      case paths if paths.nonEmpty =>
        val storageConfig = config.getConfig("apk.storage")

        val s3Client = client(AmazonClientConnectionDef.default.copy(
          credential = Some(Credential.static(
            storageConfig.getString("access-key"),
            storageConfig.getString("secret-key")
          ))
        ))

        Future.sequence {
          paths map { case (pkg, path) =>
            val actions = extractApk(Bucket(storageConfig.getString("s3-bucket")), S3Key(path))
              .flatMap { extracted =>
                Action.safe { _ => matchCred(extracted).toSet }
              }

            Future { (pkg, actions.unsafePerform(s3Client).run) }
          }
        }

      case _ => Future.failed(new Exception("No files was found"))
    }

    Await.result(processed, Duration.Inf) foreach {
      case (pkg, \/-(result)) => println(s"\n$pkg:\n${result.mkString("\n")}\n")
      case (pkg, -\/(error))  =>
        System.err.println(s"$pkg:")
        error.toThrowable.printStackTrace(System.err)
    }
  }

  def findPackageS3Path(packageName: String)(implicit ctx: CassandraAsyncContext[SnakeCase]): Future[Option[(String, String)]] = {
    import ctx._

    ctx.run {
      GooglePlayAppFiles.withPackageName(packageName) map { _.s3Key } take 1
    } map { _.headOption.map { (packageName, _) } }
  }

  def retrieveAvailableS3Paths(adNetwork: String)(implicit ctx: CassandraAsyncContext[SnakeCase]): Future[List[(String, String)]] = {
    import ctx._

    for {
      ids <- ctx.run {
        GooglePlaySdkGeneralData.withName(adNetwork) map { _.id } take 1
      }
      apps <- ctx.run {
        SdkApps.googlePlayAppsWithSdkId(ids.head) map { _.appId } take 5
      }
      paths <- Future.sequence(
        apps.map(findPackageS3Path)
      )
    } yield paths.flatten
  }

  def download(s3Bucket: Bucket, packagePath: S3Key) =
    S3.get(new ContentLocation(s3Bucket, packagePath))
      .flatMap { apk =>
        Action.safe { _ =>
          val pattern = """(.*/)?([^/]+)(\.apk)$""".r
          val pattern(_, prefix, suffix) = apk.getKey

          val toAnalyze = {
            val file = File.createTempFile(prefix + "_", suffix)
            file.deleteOnExit()
            file
          }
          IOUtils.copy(apk.getObjectContent, new FileOutputStream(toAnalyze))

          toAnalyze.getAbsolutePath
        }
      }

  def decompileApk(apkPath: String) = {
    val baseDirPath = path(tmpDir, "apkdecomp_" + System.currentTimeMillis)
    s"""apktool decode -o $baseDirPath $apkPath""".!!
    s"""apktool build $baseDirPath""".!!

    val classesDexPath = path(baseDirPath, "build", "apk", "classes.dex")
    val resultJarPath = path(baseDirPath, "classes.jar")
    s"""dex2jar -o $resultJarPath $classesDexPath""".!!

    val lookupPath = path(baseDirPath, "apksrc")
    s"""java org.benf.cfr.reader.Main $resultJarPath --outputdir $lookupPath --silent true""".!!

    lookupPath
  }

  def extractApk(s3Bucket: Bucket, packagePath: S3Key) =
    download(s3Bucket, packagePath) flatMap { apk =>
      Action.safe { _ => decompileApk(apk) }
    }

  def findAdMobCred(sourcesPath: String) =
    s"""grep -Rso -hP ca-app-pub-[0-9]{16}/[0-9]{10} $sourcesPath""".lineStream_!

  def findChartboostCred(sourcesPath: String) =
    s"""grep -Rso -P [0-9a-f]{40} $sourcesPath""".lineStream_! flatMap { row =>
      val filenameAndMatching = row.split(":")

      s"""grep -Rso -hP [0-9a-f]{24} ${filenameAndMatching(0)}""".lineStream_! map {
        (filenameAndMatching(1), _)
      }
    }

  def client(conf: AmazonClientConnectionDef) = {
    import com.amazonaws.services.s3.AmazonS3Client
    AmazonClient.withClientConfiguration[AmazonS3Client](conf, None)
  }

  def path(parts: String*) = parts.mkString(File.separator)

}
