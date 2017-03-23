package com.apptopia.labs.ads.cli

import scala.sys.process._
import com.apptopia.labs.ads.LogProvider

trait Extractors { self: LogProvider =>

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

}
