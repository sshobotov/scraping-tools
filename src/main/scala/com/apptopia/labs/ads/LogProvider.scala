package com.apptopia.labs.ads

import org.slf4j.LoggerFactory

trait LogProvider {

  lazy val log = LoggerFactory.getLogger(getClass)

}
