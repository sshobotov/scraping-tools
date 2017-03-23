package com.apptopia.labs.ads.cli

import java.util.concurrent.Executors

import com.apptopia.labs.ads.LogProvider

import scala.concurrent.ExecutionContext

object IpaAdsCredentials extends Extractors with LogProvider {

  lazy val tmpDir = System.getProperty("java.io.tmpdir")

  val adNetworks = Map(
    "adcolony" -> ("AdColony", findAdColonyCred _)
  )

  implicit val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(
    Runtime.getRuntime.availableProcessors - 1
  ))



}
