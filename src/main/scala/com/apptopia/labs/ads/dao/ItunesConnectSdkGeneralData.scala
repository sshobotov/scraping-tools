package com.apptopia.labs.ads.dao

import com.apptopia.labs.ads.models
import io.getquill.{CassandraAsyncContext, NamingStrategy}

object ItunesConnectSdkGeneralData {

  def all[N <: NamingStrategy](implicit ctx: CassandraAsyncContext[N]) = {
    import ctx._

    quote {
      querySchema[models.SdkGeneralData]("boglach.canonic_itunes_connect_sdk_general_data")
    }
  }

  def withName[N <: NamingStrategy](name: String)(implicit ctx: CassandraAsyncContext[N]) = {
    import ctx._

    quote {
      all.filter(_.name == lift(name))
    }
  }

}
