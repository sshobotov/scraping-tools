package com.apptopia.labs.ads.cli

import java.io.File

import scala.util.{Failure, Try}

object Utility {

  def path(parts: String*): String = parts.mkString(File.separator)

  def ifInterrupted[T](block: => T)(interruptedBlock: => Unit): T = {
    Try { block }
      .recoverWith { case e =>
        interruptedBlock
        Failure(e)
      }
      .get
  }

  def withFinally[T](block: => T)(finallyBlock: => Unit): T = {
    val result = Try { block }
    finallyBlock

    result.get
  }

}
