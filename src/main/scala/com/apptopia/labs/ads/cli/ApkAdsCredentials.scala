package com.apptopia.labs.ads.cli

import java.io.{File, FileOutputStream}
import java.time.LocalDateTime
import java.util.concurrent.Executors

import com.amazonaws.util.IOUtils
import com.apptopia.labs.ads.LogProvider
import com.typesafe.config.ConfigFactory
import io.atlassian.aws.s3._
import io.atlassian.aws.{AmazonClient, AmazonClientConnectionDef, Credential, AwsAction => Action}
import io.getquill.{CassandraAsyncContext, SnakeCase}
import com.apptopia.labs.ads.dao.{GooglePlayAppFiles, GooglePlaySdkGeneralData, SdkApps}
import org.rogach.scallop._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, blocking}
import scala.sys.process._
import scalaz.{-\/, \/-}

object ApkAdsCredentials extends LogProvider {

  lazy val tmpDir = System.getProperty("java.io.tmpdir")

  val adNetworks = Map(
    "admob"       -> ("Google AdMob", findAdMobCred _),
    "chartboost"  -> ("Chartboost", findChartboostCred _),
    "revmob"      -> ("Revmob", findRevmobCred _)
  )

  implicit val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(
    Runtime.getRuntime.availableProcessors - 1
  ))

  def main(args: Array[String]): Unit = {
    val arguments = new Arguments(args)
    val (adNetwork, matchCred) = adNetworks(arguments.adNetworkName())

    val config = ConfigFactory.load("local").withFallback(ConfigFactory.load())
    implicit val ctx = new CassandraAsyncContext[SnakeCase](config.getConfig("cassandra"))

    val toProcess =
      if (arguments.packageName.isDefined) findPackageS3Path(arguments.packageName()) map { _.toList }
      else retrieveAvailableS3Paths(adNetwork, arguments)

    val processed = toProcess flatMap {
      case paths if paths.nonEmpty =>
        log.info("Processing fetched paths...")
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

            Future {
              blocking { (pkg, actions.unsafePerform(s3Client).run) }
            }
          }
        }

      case _ => Future.failed(new Exception("No files was found"))
    }

    Await.result(processed, Duration.Inf) foreach {
      case (pkg, \/-(result)) =>
        log.info(s"\n$pkg:\n${if (result.isEmpty) "not found" else result.mkString("\n")}\n")

      case (pkg, -\/(error))  =>
        log.error(s"$pkg:")
        error.toThrowable.printStackTrace(System.err)
    }
    log.info("Done")
  }

  def findPackageS3Path(packageName: String)(implicit ctx: CassandraAsyncContext[SnakeCase]): Future[Option[(String, String)]] = {
    import ctx._

    ctx.run {
      GooglePlayAppFiles.withPackageName(packageName)
    } map {
      _.filter(_.s3Key.nonEmpty).headOption map { entry =>
        (packageName, entry.s3Key.get)
      }
    } recoverWith {
      case e => Future.failed(new Exception(s"[$packageName] ${e.getMessage}", e.getCause))
    }
  }

  def retrieveAvailableS3Paths(adNetwork: String, args: Arguments)(implicit ctx: CassandraAsyncContext[SnakeCase]): Future[List[(String, String)]] = {
    log.info(s"Fetching s3 paths for $adNetwork with offset ${args.offset()} and limit ${args.limit()}...")
    import ctx._

    for {
      ids <- ctx.run {
        GooglePlaySdkGeneralData.withName(adNetwork) map { _.id } take 1
      }
      apps <- fetchSdkAppsWithManualFiltering(args.offset(), args.limit())(ids.head)
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
    log.info(s"Decompiling $apkPath...")

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

  def extractApk(s3Bucket: Bucket, packagePath: S3Key) = {
    log.info(s"Downloading $packagePath...")

    download(s3Bucket, packagePath) flatMap { apk =>
      Action.safe { _ => decompileApk(apk) }
    }
  }

  def findAdMobCred(sourcesPath: String) =
    s"""grep -Rso -P -h ca-app-pub-[0-9]{16}/[0-9]{10} $sourcesPath""".lineStream_!

  def findChartboostCred(sourcesPath: String) =
    s"""grep -Rsow -P [0-9a-f]{40} $sourcesPath""".lineStream_! flatMap { row =>
      val filenameAndMatching = row.split(":")

      val withAppID = s"""grep -Rsow -P -h [0-9a-f]{24} ${filenameAndMatching(0)}""".lineStream_! map {
        (filenameAndMatching(1), _)
      }
      if (withAppID.isEmpty) log.info(s"Only first match for: $row")

      withAppID
    }

  def findRevmobCred(sourcesPath: String) =
    s"""grep -Rsow -P -h [0-9a-f]{24} $sourcesPath""".lineStream_!

  def client(conf: AmazonClientConnectionDef) = {
    import com.amazonaws.services.s3.AmazonS3Client
    AmazonClient.withClientConfiguration[AmazonS3Client](conf, None)
  }

  def path(parts: String*) = parts.mkString(File.separator)

  private class Arguments(arguments: Seq[String]) extends ScallopConf(arguments) {
    val packageName = opt[String](name = "package", descr = "Name of single package to process")

    val offset = opt[Int](descr = "Number of packages to skip from the start", default = Some(0))
    val limit = opt[Int](descr = "Number of packages to process", default = Some(100))

    val adNetworkName = trailArg[String](descr = "Ad network to process")

    validate(adNetworkName) { value =>
      if (!adNetworks.contains(value))
      Left(s"Provide valid ad network name, supported: ${adNetworks.keys.mkString(" ,")}")
      else
      Right(Unit)
    }

    conflicts(packageName, List(offset, limit))

    verify()
  }

  // Workaround for Cassandra limitations
  private def fetchSdkAppsWithManualFiltering(offset: Int, limit: Int)(id: Int)(implicit ctx: CassandraAsyncContext[SnakeCase]): Future[List[String]] = {
    import ctx._

    val queriedValueProbability = 0.05
    type Entries = List[(String, Option[Boolean])]

    def fetchRecursively(offset: Int, preFilterOffset: Int = 0, acc: Entries = Nil): Future[Entries] = {
      val queryLimit = ((limit + offset) / queriedValueProbability).toInt + preFilterOffset
      log.info(s"Doing SdkApps.googlePlayAppsWithSdkId($id) request...")

      ctx.run {
        SdkApps.googlePlayAppsWithSdkId(id, year = LocalDateTime.now.minusMonths(3).getYear)
          .map(row => (row.appId, row.active))
          .take(lift(queryLimit))
      } flatMap { rows =>
        val filtered = rows
          .drop(preFilterOffset)
          .filter{_._2.getOrElse(false)}
          .drop(offset)

        if (rows.lengthCompare(queryLimit) >= 0 && filtered.lengthCompare(limit) < 0)
          fetchRecursively(0, queryLimit, acc ++ filtered)
        else
          Future.successful(acc ++ filtered)
      }
    }
    fetchRecursively(offset) map {
      _ take limit map { _._1 }
    }
  }

}
