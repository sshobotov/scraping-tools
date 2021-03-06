package com.apptopia.labs.ads.cli

import scala.sys.process._
import com.apptopia.labs.ads.LogProvider

trait Extractors { self: LogProvider =>

  def findAdMobCred(sourcesPath: String) =
    s"""grep -Rso -P -h ca-app-pub-[0-9]{16}/[0-9]{10} $sourcesPath""".lineStream_!

  def findChartboostCred(sourcesPath: String) =
    s"""grep -Rsow -P [0-9a-f]{40} $sourcesPath""".lineStream_!
      .zipWithIndex
      .map { case (row, idx) =>
        val filenameAndMatching = row.split(":")

        val withAppID = findSymbolsHex(filenameAndMatching(0), 24) map {
          (filenameAndMatching(1), _)
        }
        if (withAppID.isEmpty) log.warn(s"Only first match for: $row")

        withAppID(idx)
      }

  def findRevmobCred(sourcesPath: String) = findSymbolsHex(sourcesPath, 24)

  def findVungleCred(sourcesPath: String) = findSymbolsHex(sourcesPath, 24)

  def findFacebookAudienceCred(sourcesPath: String) =
    s"""grep -Rsow -P -h [0-9]{16}_[0-9]{16} $sourcesPath""".lineStream_!

  def findAdColonyCred(sourcesPath: String) =
    s"""grep -Rsowa -P app[0-9a-f]{18} $sourcesPath""".lineStream_!
      .zipWithIndex
      .map { case (row, idx) =>
        val filenameAndMatching = row.split(":")

        val withAppID = s"""grep -Rsowa -P vz[0-9a-f]{18} ${filenameAndMatching(0)}""".lineStream_! map {
          (filenameAndMatching(1), _)
        }
        if (withAppID.isEmpty) log.warn(s"Only first match for: $row")

        withAppID(idx)
      }

  def findMoPubCred(sourcesPath: String) = findSymbolsHex(sourcesPath, 32)

  private def findSymbolsHex(sourcesPath: String, size: Int) =
    s"""grep -Rsow -P -h [0-9a-f]{$size} $sourcesPath""".lineStream_!

}
