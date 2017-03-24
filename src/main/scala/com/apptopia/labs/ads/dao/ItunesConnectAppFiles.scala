package com.apptopia.labs.ads.dao

import com.apptopia.labs.ads.models.ItunesConnectAppFile
import io.getquill.{CassandraAsyncContext, NamingStrategy}

object ItunesConnectAppFiles {

  def all[N <: NamingStrategy](implicit ctx: CassandraAsyncContext[N]) = {
    import ctx._

    quote {
      querySchema[ItunesConnectAppFile]("boglach.canonic_itunes_connect_app_files")
    }
  }

  def withAppleAppId[N <: NamingStrategy](appleAppId: String)(implicit ctx: CassandraAsyncContext[N]) = {
    import ctx._

    quote {
      all
        .filter(_.vndAppleAppId == lift(appleAppId))
        .sortBy(_.versionCode)(Ord.desc)
    }
  }

}
