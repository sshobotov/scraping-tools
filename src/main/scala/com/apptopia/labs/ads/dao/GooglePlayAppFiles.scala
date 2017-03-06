package com.apptopia.labs.ads.dao

import com.apptopia.labs.ads.models.GooglePlayAppFile
import io.getquill.{CassandraAsyncContext, NamingStrategy}

object GooglePlayAppFiles {

  def all[N <: NamingStrategy](implicit ctx: CassandraAsyncContext[N]) = {
    import ctx._

    quote {
      querySchema[GooglePlayAppFile]("boglach.canonic_google_play_app_files")
    }
  }

  def withPackageName[N <: NamingStrategy](packageName: String)(implicit ctx: CassandraAsyncContext[N]) = {
    import ctx._

    quote {
      all
        .filter(_.vndPackageName == lift(packageName))
        .sortBy(_.versionCode)(Ord.desc)
    }
  }

}
