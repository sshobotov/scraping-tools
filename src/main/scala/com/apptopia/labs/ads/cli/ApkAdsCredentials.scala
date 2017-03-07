package com.apptopia.labs.ads.cli

import java.io.{File, FileOutputStream, FileWriter, PrintWriter}
import java.nio.file.{Files, Paths}
import java.time.LocalDateTime
import java.util.concurrent.Executors

import com.amazonaws.util.IOUtils
import com.apptopia.labs.ads.LogProvider
import com.typesafe.config.ConfigFactory
import io.atlassian.aws.s3._
import io.atlassian.aws.{AmazonClient, AmazonClientConnectionDef, Credential, AwsAction => Action}
import io.getquill.{CassandraAsyncContext, SnakeCase}
import com.apptopia.labs.ads.dao.{GooglePlayAppFiles, GooglePlaySdkGeneralData, SdkApps}
import org.apache.commons.io.FileUtils
import org.rogach.scallop._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, blocking}
import scala.sys.process._
import scala.util.{Failure, Try}
import scalaz.{-\/, \/-}

object ApkAdsCredentials extends LogProvider {

  lazy val tmpDir = System.getProperty("java.io.tmpdir")

  val adNetworks = Map(
    "admob"       -> ("Google AdMob", findAdMobCred _),
    "chartboost"  -> ("Chartboost", findChartboostCred _),
    "revmob"      -> ("Revmob", findRevmobCred _),
    "vungle"      -> ("Vungle", findVungleCred _),
    "facebook"    -> ("Facebook Audience Network", findFacebookAudienceCred _),
    "adcolony"    -> ("AdColony", findAdColonyCred _)
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
      arguments.packageName.toOption map { packageName =>
        findPackageS3Path(packageName) map { _.toList }
      } getOrElse {
        retrieveAvailableS3Paths(adNetwork, arguments)
      }

    val fileOutput = arguments.outputFile.toOption.map { file =>
      if (!file.exists) file.createNewFile()
      new PrintWriter(new FileWriter(file, true))
    }
    val processed = toProcess flatMap {
      case paths if paths.nonEmpty =>
        log.debug("Processing fetched paths...")
        val storageConfig = config.getConfig("apk.storage")

        val s3Client = client(AmazonClientConnectionDef.default.copy(
          credential = Some(Credential.static(
            storageConfig.getString("access-key"),
            storageConfig.getString("secret-key")
          ))
        ))

        Future.sequence {
          paths map { case (pkg, s3Path) =>
            val destinationDir = path(tmpDir, s"extracted_${pkg}_${System.currentTimeMillis}")
            val actions = extractApk(Bucket(storageConfig.getString("s3-bucket")), S3Key(s3Path), destinationDir)
              .flatMap { extracted =>
                Action.safe { _ =>
                  withFinally {
                    matchCred(extracted).toSet
                  } {
                    FileUtils.deleteDirectory(Paths.get(destinationDir).toFile)
                  }
                }
              }

            Future {
              blocking { actions.unsafePerform(s3Client).run }
            } map { result =>
              for {
                out         <- fileOutput
                credentials <- result.toOption if credentials.nonEmpty
              } yield out.println(s"$pkg ${credentials.mkString("[", ", ", "]")}")

              (pkg, result)
            }
          }
        }

      case _ => Future.failed(new Exception("No files was found"))
    }

    case class Collected(ok: Seq[(String, Seq[Any])], notFound: Seq[String], err: Seq[(String, Throwable)])

    val collected = Await.result(processed, Duration.Inf)
      .foldLeft[Collected](Collected(Seq.empty, Seq.empty, Seq.empty)) {
        case (acc, (pkg, \/-(result))) =>
          if (result.nonEmpty) {
            acc.copy(ok = acc.ok :+ (pkg, result.toSeq))
          } else {
            acc.copy(notFound = acc.notFound :+ pkg)
          }

        case (acc, (pkg, -\/(error))) =>
          acc.copy(err = acc.err :+ (pkg, error.toThrowable.getCause))
      }

    fileOutput.foreach(_.close)

    log.info(s"Found credential entries:   ${collected.ok.size}")
    log.info(s"Packages with no entries:   ${collected.notFound.size}")
    log.info(s"Packages failed to process: ${collected.err.size}")

    if (fileOutput.isEmpty && collected.ok.nonEmpty) {
      log.info("\nFound:")
      collected.ok foreach { case (pkg, entry) =>
        log.info(s"$pkg ${entry.mkString("[", ", ", "]")}")
      }
    }
    if (collected.notFound.nonEmpty) {
      log.debug("\nNo matches:")
      collected.notFound foreach log.debug
    }
    if (collected.err.nonEmpty) {
      log.warn("\nErrors:")
      collected.err foreach { case (pkg, e) =>
        log.warn(pkg, e)
      }
    }
  }

  def findPackageS3Path(packageName: String)
                       (implicit ctx: CassandraAsyncContext[SnakeCase]): Future[Option[(String, String)]] = {
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

  def retrieveAvailableS3Paths(adNetwork: String, args: Arguments)
                              (implicit ctx: CassandraAsyncContext[SnakeCase]): Future[List[(String, String)]] = {
    log.debug(s"Fetching s3 paths for $adNetwork with offset ${args.offset()} and limit ${args.limit()}...")
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

          val toAnalyze = File.createTempFile(prefix + "_", suffix)
          ifInterrupted {
            IOUtils.copy(apk.getObjectContent, new FileOutputStream(toAnalyze))
          } {
            toAnalyze.delete()
          }

          toAnalyze.getAbsolutePath
        }
      }

  def decompileApk(apkPath: String, destinationDir: String) = {
    log.debug(s"Decompiling $apkPath...")

    s"""apktool decode -o $destinationDir $apkPath""".!!
    s"""apktool build $destinationDir""".!!

    val classesDexPath = path(destinationDir, "build", "apk", "classes.dex")
    val resultJarPath = path(destinationDir, "classes.jar")
    s"""dex2jar -o $resultJarPath $classesDexPath""".!!

    val lookupPath = path(destinationDir, "apksrc")
    s"""java org.benf.cfr.reader.Main $resultJarPath --outputdir $lookupPath --silent true""".!!

    lookupPath
  }

  def extractApk(s3Bucket: Bucket, packagePath: S3Key, destinationDir: String) = {
    log.debug(s"Downloading $packagePath...")

    download(s3Bucket, packagePath) flatMap { apk =>
      Action.safe { _ =>
        withFinally {
          decompileApk(apk, destinationDir)
        } {
          Files.delete(Paths.get(apk))
        }
      }
    }
  }

  def findAdMobCred(sourcesPath: String) =
    s"""grep -Rso -P -h ca-app-pub-[0-9]{16}/[0-9]{10} $sourcesPath""".lineStream_!

  def findChartboostCred(sourcesPath: String) =
    s"""grep -Rsow -P [0-9a-f]{40} $sourcesPath""".lineStream_! flatMap { row =>
      val filenameAndMatching = row.split(":")

      val withAppID = find24SymbolsHex(filenameAndMatching(0)) map {
        (filenameAndMatching(1), _)
      }
      if (withAppID.isEmpty) log.warn(s"Only first match for: $row")

      withAppID
    }

  def findRevmobCred(sourcesPath: String) = find24SymbolsHex(sourcesPath)

  def findVungleCred(sourcesPath: String) = find24SymbolsHex(sourcesPath)

  def findFacebookAudienceCred(sourcesPath: String) =
    s"""grep -Rsow -P -h [0-9]{16}_[0-9]{16} $sourcesPath""".lineStream_!

  def findAdColonyCred(sourcesPath: String) =
    s"""grep -Rsow -P app[0-9a-f]{18} $sourcesPath""".lineStream_! flatMap { row =>
      val filenameAndMatching = row.split(":")

      val withAppID = s"""grep -Rsow -P vz[0-9a-f]{18} ${filenameAndMatching(0)}""".lineStream_! map {
        (filenameAndMatching(1), _)
      }
      if (withAppID.isEmpty) log.warn(s"Only first match for: $row")

      withAppID
    }

  private def find24SymbolsHex(sourcesPath: String) =
    s"""grep -Rsow -P -h [0-9a-f]{24} $sourcesPath""".lineStream_!

  def client(conf: AmazonClientConnectionDef) = {
    import com.amazonaws.services.s3.AmazonS3Client
    AmazonClient.withClientConfiguration[AmazonS3Client](conf, None)
  }

  private class Arguments(arguments: Seq[String]) extends ScallopConf(arguments) {
    val packageName = opt[String](name = "package", descr = "Name of single package to process")

    val offset = opt[Int](descr = "Number of packages to skip from the start", default = Some(0))
    val limit = opt[Int](descr = "Number of packages to process", default = Some(100))
    val outputFile = opt[File](descr = "File path to output results to")

    val adNetworkName = trailArg[String](descr = "Ad network to process")

    validate(adNetworkName) { value =>
      if (!adNetworks.contains(value))
        Left(s"Provide valid ad network name, supported: ${adNetworks.keys.mkString(" ,")}")
      else
        Right(Unit)
    }

    conflicts(packageName, List(offset, limit, outputFile))

    verify()
  }

  // Workaround for Cassandra limitations
  private def fetchSdkAppsWithManualFiltering(offset: Int, limit: Int)(id: Int)
                                             (implicit ctx: CassandraAsyncContext[SnakeCase]): Future[List[String]] = {
    import ctx._

    val queriedValueProbability = 0.05
    type Entries = List[(String, Option[Boolean])]

    def fetchRecursively(offset: Int, preFilterOffset: Int = 0, acc: Entries = Nil): Future[Entries] = {
      val queryLimit = ((limit + offset) / queriedValueProbability).toInt + preFilterOffset
      log.debug(s"Doing SdkApps.googlePlayAppsWithSdkId($id) request...")

      ctx.run {
        SdkApps.googlePlayAppsWithSdkId(id, year = LocalDateTime.now.minusMonths(3).getYear)
          .map(row => (row.appId, row.active))
          .take(lift(queryLimit))
      } flatMap { rows =>
        val filtered = rows
          .drop(preFilterOffset)
          .filter{_._2.getOrElse(true)}
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

  private def path(parts: String*) = parts.mkString(File.separator)

  private def ifInterrupted[T](block: => T)(interruptedBlock: => Unit): T = {
    Try { block }
      .recoverWith { case e =>
        interruptedBlock
        Failure(e)
      }
      .get
  }

  private def withFinally[T](block: => T)(finallyBlock: => Unit): T = {
    val result = Try { block }
    finallyBlock

    result.get
  }

}
