package com.apptopia.labs.ads.cli

import java.io.{File, FileOutputStream, FileWriter, PrintWriter}
import java.nio.file.{Files, Paths}
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
import scalaz.{-\/, \/-}

import Utility._

object ApkAdsCredentials extends Extractors with ResultsOutput with LogProvider {

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
            Future {
              blocking {
                matchCredentials(pkg, Bucket(storageConfig.getString("s3-bucket")), S3Key(s3Path), matchCred)
                  .unsafePerform(s3Client)
                  .run
              }
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
    stats(collected, fileOutput.nonEmpty)
  }

  private def matchCredentials[T](pkg: String, bucket: Bucket, s3Key: S3Key, matcher: String => Stream[T]) = {
    val destinationDir = path(tmpDir, s"extracted_${pkg}_${System.currentTimeMillis}")
    extractApk(bucket, s3Key, destinationDir)
      .flatMap { extracted =>
        Action.safe { _ =>
          withFinally {
            matcher(extracted).toSet
          } {
            FileUtils.deleteDirectory(Paths.get(destinationDir).toFile)
          }
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
      apps <- {
        log.debug(s"Doing SdkApps.fetchPaged(${ids.head}) request...")
        SdkApps.fetchPaged(args.offset(), args.limit())("google_play", ids.head)
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

}
