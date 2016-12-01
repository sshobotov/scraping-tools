package com.apptopia.labs.ads.models

case class SdkApp(storeType: String, sdkId: Int, present: Boolean, active: Option[Boolean], appId: String, categoryId: Int, year: Int, date: String)
