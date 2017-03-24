package com.apptopia.labs.ads.cli

import com.apptopia.labs.ads.LogProvider

trait ResultsOutput { self: LogProvider =>

  case class Collected(ok: Seq[(String, Seq[Any])], notFound: Seq[String], err: Seq[(String, Throwable)])

  def stats(collected: Collected, hasPersistentOutput: Boolean): Unit = {
    log.info(s"Found credential entries:   ${collected.ok.size}")
    log.info(s"Packages with no entries:   ${collected.notFound.size}")
    log.info(s"Packages failed to process: ${collected.err.size}")

    if (!hasPersistentOutput && collected.ok.nonEmpty) {
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

}
