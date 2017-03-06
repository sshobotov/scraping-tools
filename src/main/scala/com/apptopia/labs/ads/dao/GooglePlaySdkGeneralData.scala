package com.apptopia.labs.ads.dao

import com.apptopia.labs.ads.models
import io.getquill.{CassandraAsyncContext, NamingStrategy}

object GooglePlaySdkGeneralData {

  def all[N <: NamingStrategy](implicit ctx: CassandraAsyncContext[N]) = {
    import ctx._

    quote {
      querySchema[models.GooglePlaySdkGeneralData]("boglach.canonic_google_play_sdk_general_data")
    }
  }

  def withName[N <: NamingStrategy](name: String)(implicit ctx: CassandraAsyncContext[N]) = {
    import ctx._

    quote {
      all.filter(_.name == lift(name))
    }
  }

}
