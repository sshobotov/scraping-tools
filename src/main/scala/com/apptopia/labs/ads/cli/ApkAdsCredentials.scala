package com.apptopia.labs.ads.cli

import java.io.{File, FileOutputStream}

import com.amazonaws.util.IOUtils
import io.atlassian.aws.s3._
import io.atlassian.aws.{AmazonClient, AmazonClientConnectionDef, Credential, AwsAction => Action}

import scala.sys.process._
import scalaz.{-\/, \/-}

object ApkAdsCredentials {

  def main(args: Array[String]): Unit = {
    assert(args.length > 0, "Package path should be provided")
    val packagePath = S3Key(args(0))

    val envAWSKeyID = assertAndGetEnv("AWS_ACCESS_KEY_ID")
    val envAWSSecretKey = assertAndGetEnv("AWS_SECRET_ACCESS_KEY")
    val envS3Bucket = Bucket(assertAndGetEnv("APK_S3_BUCKET"))

    val s3Client = client(AmazonClientConnectionDef.default.copy(
      credential = Some(Credential.static(envAWSKeyID, envAWSSecretKey))
    ))

    val actions = S3.get(new ContentLocation(envS3Bucket, packagePath))
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
      .flatMap { localApk =>
        Action.safe { _ =>
          val tmpDir = System.getProperty("java.io.tmpdir")
          val baseDirPath = path(tmpDir, "apkdecomp_" + System.currentTimeMillis)
          s"""apktool decode -o $baseDirPath $localApk""".!!
          s"""apktool build $baseDirPath""".!!

          val classesDexPath = path(baseDirPath, "build", "apk", "classes.dex")
          val resultJarPath = path(baseDirPath, "classes.jar")
          s"""dex2jar -o $resultJarPath $classesDexPath""".!!

          val lookupPath = path(baseDirPath, "apksrc")
          s"""java org.benf.cfr.reader.Main $resultJarPath --outputdir $lookupPath --silent true""".!!

          lookupPath
        }
      }

    actions.unsafePerform(s3Client).run match {
      case \/-(result) => println(s"\n$result\n")
      case -\/(error)  => error.toThrowable.printStackTrace(System.err)
    }
  }

  def assertAndGetEnv(key: String): String = {
    assert(scala.sys.env.contains(key), s"Env $key should be provided")
    scala.sys.env(key)
  }

  def client(conf: AmazonClientConnectionDef) = {
    import com.amazonaws.services.s3.AmazonS3Client
    AmazonClient.withClientConfiguration[AmazonS3Client](conf, None)
  }

  def path(parts: String*) = parts.mkString(File.separator)

}