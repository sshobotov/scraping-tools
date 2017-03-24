package com.apptopia.labs.ads.cli

import java.io.{File, FileOutputStream, FileWriter, PrintWriter}
import java.net.URL
import java.nio.file.Paths
import java.util.concurrent.Executors

import com.apptopia.labs.ads.LogProvider
import com.apptopia.labs.ads.dao.{ItunesConnectAppFiles, ItunesConnectSdkGeneralData, SdkApps}
import com.typesafe.config.ConfigFactory
import io.getquill.{CassandraAsyncContext, SnakeCase}
import org.rogach.scallop.ScallopConf

import scala.concurrent.{Await, ExecutionContext, Future, blocking}
import scala.sys.process._
import Utility._
import org.apache.commons.io.{FileUtils, IOUtils}

import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}

object IpaAdsCredentials extends Extractors with ResultsOutput with LogProvider {

  lazy val tmpDir = System.getProperty("java.io.tmpdir")

  val adNetworks = Map(
    "adcolony" -> ("AdColony", findAdColonyCred _)
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
      arguments.appleAppId.toOption map { appleAppId =>
        findIpaPath(appleAppId) map { _.toList }
      } getOrElse {
        retrieveAvailableIpaPaths(adNetwork, arguments)
      }

    val fileOutput = arguments.outputFile.toOption.map { file =>
      if (!file.exists) file.createNewFile()
      new PrintWriter(new FileWriter(file, true))
    }
    val processed = toProcess flatMap {
      case paths if paths.nonEmpty =>
        log.debug("Processing fetched paths...")

        Future.sequence {
          paths map { case (appleAppId, ipaPath) =>
            Future {
              blocking { matchCredentials(appleAppId, ipaPath, matchCred) }
            } map { result =>
              for {
                out         <- fileOutput
                credentials <- result.toOption if credentials.nonEmpty
              } yield out.println(s"$appleAppId ${credentials.mkString("[", ", ", "]")}")

              (appleAppId, result)
            }
          }
        }

      case _ => Future.failed(new Exception("No files was found"))
    }

    val collected = Await.result(processed, Duration.Inf)
      .foldLeft[Collected](Collected(Seq.empty, Seq.empty, Seq.empty)) {
        case (acc, (appleAppId, Success(result))) =>
          if (result.nonEmpty) {
            acc.copy(ok = acc.ok :+ (appleAppId, result.toSeq))
          } else {
            acc.copy(notFound = acc.notFound :+ appleAppId)
          }

        case (acc, (pkg, Failure(error))) =>
          acc.copy(err = acc.err :+ (pkg, error))
    }

    fileOutput.foreach(_.close)
    stats(collected, fileOutput.nonEmpty)
  }

  private def matchCredentials(appleAppId: String, ipaUrl: String, matcher: String => Stream[(String, String)]) = Try {
    val targetFile = download(ipaUrl)
    val destinationDir = path(tmpDir, s"extracted_${appleAppId}_${System.currentTimeMillis}")

    withFinally {
      extractIpa(targetFile.getAbsolutePath, destinationDir)

      withFinally {
        matcher(destinationDir).toSet
      } {
        FileUtils.deleteDirectory(Paths.get(destinationDir).toFile)
      }
    } {
      targetFile.delete()
    }
  }

  private def findIpaPath(appleAppId: String)
                         (implicit ctx: CassandraAsyncContext[SnakeCase]): Future[Option[(String, String)]] = {
    import ctx._

    ctx.run {
      ItunesConnectAppFiles.withAppleAppId(appleAppId.toInt)
    } map {
      _
        .filter(_.ipaUrl.nonEmpty)
        .headOption
        .flatMap {
          _.ipaUrl map { (appleAppId, _) }
        }
    } recoverWith {
      case e => Future.failed(new Exception(s"[$appleAppId] ${e.getMessage}", e.getCause))
    }
  }

  private def retrieveAvailableIpaPaths(adNetwork: String, args: Arguments)
                                       (implicit ctx: CassandraAsyncContext[SnakeCase]): Future[List[(String, String)]] = {
    log.debug(s"Fetching IPA paths for $adNetwork with offset ${args.offset()} and limit ${args.limit()}...")
    import ctx._

    for {
      ids <- ctx.run {
        ItunesConnectSdkGeneralData.withName(adNetwork) map { _.id } take 1
      }
      apps <- {
        log.debug(s"Doing SdkApps.fetchPaged(${ids.head}) request...")
        SdkApps.fetchPaged(args.offset(), args.limit())("itunes_connect", ids.head)
      }
      paths <- Future.sequence(
        apps.map(findIpaPath)
      )
    } yield paths.flatten
  }

  private def download(url: String) = {
    val target = new URL(url)

    val pattern = """(.*/)?([^/]+)(\.ipa)$""".r
    val pattern(_, prefix, suffix) = target.toURI.getPath

    val saveTo = File.createTempFile(prefix + "_", suffix)

    ifInterrupted {
      val stream = target.openStream()
      withFinally {
        IOUtils.copy(stream, new FileOutputStream(saveTo))
      } {
        stream.close()
      }
    } {
      saveTo.delete()
    }

    saveTo
  }

  private def extractIpa(target: String, destinationDir: String) =
    s"unzip $target -d $destinationDir".!!

  private class Arguments(arguments: Seq[String]) extends ScallopConf(arguments) {
    val appleAppId = opt[String](name = "id", descr = "iTunes ID of single application to process")

    val offset = opt[Int](descr = "Number of applications to skip from the start", default = Some(0))
    val limit = opt[Int](descr = "Number of applications to process", default = Some(100))
    val outputFile = opt[File](descr = "File path to output results to")

    val adNetworkName = trailArg[String](descr = "Ad network to process")

    validate(adNetworkName) { value =>
      if (!adNetworks.contains(value))
        Left(s"Provide valid ad network name, supported: ${adNetworks.keys.mkString(" ,")}")
      else
        Right(Unit)
    }

    conflicts(appleAppId, List(offset, limit, outputFile))

    verify()
  }

}
