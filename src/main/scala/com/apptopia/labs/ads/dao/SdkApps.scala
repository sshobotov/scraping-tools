package com.apptopia.labs.ads.dao

import java.time.{LocalDateTime, Year}

import com.apptopia.labs.ads.models.SdkApp
import io.getquill.{CassandraAsyncContext, NamingStrategy, SnakeCase}

import scala.concurrent.{ExecutionContext, Future}

object SdkApps {

  def all[N <: NamingStrategy](implicit ctx: CassandraAsyncContext[N]) = {
    import ctx._

    quote {
      querySchema[SdkApp]("tinkerbell.sdk_apps")
    }
  }

  def withSdkId[N <: NamingStrategy](store: String, sdkId: Int, year: Int = Year.now.getValue)
                                                  (implicit ctx: CassandraAsyncContext[N]) = {
    import ctx._

    quote {
      all
        .filter(_.storeType == lift(store))
        .filter(_.present == true)
        .filter(_.year == lift(year))
        .filter(_.sdkId == lift(sdkId))
        .sortBy(_.date)(Ord.desc)
    }
  }

  // Workaround for Cassandra limitations
  def fetchPaged(offset: Int, limit: Int)(store: String, id: Int)
                (implicit ctx: CassandraAsyncContext[SnakeCase], ec: ExecutionContext): Future[List[String]] = {
    import ctx._

    val queriedValueProbability = 0.05
    type Entries = List[(String, Option[Boolean])]

    def fetchRecursively(offset: Int, preFilterOffset: Int = 0, acc: Entries = Nil): Future[Entries] = {
      val queryLimit = ((limit + offset) / queriedValueProbability).toInt + preFilterOffset

      ctx.run {
        withSdkId(store, id, year = LocalDateTime.now.minusMonths(3).getYear)
          .map(row => (row.appId, row.active))
          .take(lift(queryLimit))
      } flatMap { rows =>
        val filtered = rows
          .drop(preFilterOffset)
          .filter{_._2.getOrElse(true)}
          .drop(offset)

        if (rows.lengthCompare(queryLimit) >= 0 && filtered.lengthCompare(limit) < 0)
          fetchRecursively(0, queryLimit, acc ++ filtered)
        else
          Future.successful(acc ++ filtered)
      }
    }
    fetchRecursively(offset) map {
      _ take limit map { _._1 }
    }
  }

}
