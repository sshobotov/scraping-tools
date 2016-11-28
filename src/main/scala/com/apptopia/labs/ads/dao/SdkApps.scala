package com.apptopia.labs.ads.dao

import java.time.Year

import com.apptopia.labs.ads.models.SdkApp
import io.getquill.{CassandraAsyncContext, NamingStrategy}

object SdkApps {

  def all[N <: NamingStrategy](implicit ctx: CassandraAsyncContext[N]) = {
    import ctx._

    quote {
      query[SdkApp].schema(_.entity("tinkerbell.sdk_apps"))
    }
  }

  def googlePlayAppsWithSdkId[N <: NamingStrategy](sdkId: Int, year: Int = Year.now.getValue)
                                                  (implicit ctx: CassandraAsyncContext[N]) = {
    import ctx._

    quote {
      all
        .filter(_.storeType == "google_play")
        .filter(_.present == true)
        .filter(_.year == lift(year))
        .filter(_.sdkId == lift(sdkId))
    }
  }

}
