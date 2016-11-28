package com.apptopia.labs.ads.cli

import java.io.{File, FileOutputStream}

import com.amazonaws.util.IOUtils
import com.apptopia.labs.ads.dao.{GooglePlayAppFiles, GooglePlaySdkGeneralData, SdkApps}
import com.typesafe.config.ConfigFactory
import io.atlassian.aws.s3._
import io.atlassian.aws.{AmazonClient, AmazonClientConnectionDef, Credential, AwsAction => Action}
import io.getquill.{CassandraAsyncContext, SnakeCase}

import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future
import scala.sys.process._
import scalaz.{-\/, \/-}

object ApkAdsCredentials {

  lazy val tmpDir = System.getProperty("java.io.tmpdir")

  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.load("local").withFallback(ConfigFactory.load())
    implicit val ctx = new CassandraAsyncContext[SnakeCase](config.getConfig("cassandra"))

    val toProcess =
      if (args.length > 0) findPackageS3Path(args(0)) map { _.toList }
      else retrieveAvailableS3Paths

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
          paths map { path =>
            val actions = extractApk(Bucket(storageConfig.getString("s3-bucket")), S3Key(path))
              .flatMap { extracted =>
                Action.safe { _ => findAdMobCred(extracted) }
              }

            Future { actions.unsafePerform(s3Client).run }
          }
        }

      case _ => Future.failed(new Exception("No files was found"))
    }

    processed foreach {
      _ foreach {
        case \/-(result) => println(s"\n$result\n")
        case -\/(error)  => error.toThrowable.printStackTrace(System.err)
      }
    }
    processed.failed foreach { _.printStackTrace(System.err) }
  }

  def findPackageS3Path(packageName: String)(implicit ctx: CassandraAsyncContext[SnakeCase]): Future[Option[String]] = {
    import ctx._

    ctx.run {
      GooglePlayAppFiles.withPackageName(packageName) map { _.s3Key } take 1
    } map { _.headOption }
  }

  def retrieveAvailableS3Paths(implicit ctx: CassandraAsyncContext[SnakeCase]): Future[List[String]] = {
    import ctx._

    for {
      ids <- ctx.run {
        GooglePlaySdkGeneralData.withName("Google AdMob") map { _.id } take 1
      }
      apps <- ctx.run {
        SdkApps.googlePlayAppsWithSdkId(ids.head) map { _.appId }
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
    s"""grep -Rhs 'ca-app-pub-'""".!! split "\n" foreach println

  def client(conf: AmazonClientConnectionDef) = {
    import com.amazonaws.services.s3.AmazonS3Client
    AmazonClient.withClientConfiguration[AmazonS3Client](conf, None)
  }

  def path(parts: String*) = parts.mkString(File.separator)

}
