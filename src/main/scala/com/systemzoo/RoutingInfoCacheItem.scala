package com.systemzoo

import spray.json._

import scala.util.Random

case class RoutingInfoCacheItem(info: RoutingInfo)
object RoutingInfoCacheItem {
  def fromJson(json: String): RoutingInfoCacheItem = {
    RoutingInfoCacheItem(RoutingInfo.format.read(json.parseJson))
  }
}

case class ServiceRevision(revision: Int, trafficRatio: Double, primary: Boolean)
object ServiceRevision extends DefaultJsonProtocol {
  implicit val format = jsonFormat3(ServiceRevision.apply)
}

//we are assuming here that the sum of trafficRatios for all the revisions is 1, and only one revision is primary
case class RoutingInfo(mirrorMode: Boolean, revisions: Seq[ServiceRevision]) {

  private val cutoffs = revisions.foldLeft(Seq.empty[CutoffRevision]){(accumulated, sr) =>
    val cutoffSoFar = accumulated.reverse.headOption.getOrElse(CutoffRevision(0,0)).cutoff
    accumulated :+ CutoffRevision(cutoffSoFar + sr.trafficRatio, sr.revision)
  }

  private val cutoffsNoPrimary = revisions.filterNot(_.primary).foldLeft(Seq.empty[CutoffRevision]){(accumulated, sr) =>
    val cutoffSoFar = accumulated.reverse.headOption.getOrElse(CutoffRevision(0,0)).cutoff
    accumulated :+ CutoffRevision(cutoffSoFar + sr.trafficRatio, sr.revision)
  }

  val primaryRevision = revisions.filter(_.primary).head.revision

  def getRevision: Int = {
    val random = Random.nextDouble()
    val cr = cutoffs.toStream.dropWhile(x => x.cutoff < random).headOption

    //in case we have some weird math issues fallback to primaryRevision
    if(cr.isDefined) cr.get.revision else primaryRevision
  }

  def getAlternateRevision: Option[Int] = {
    val random = Random.nextDouble()
    val cr = cutoffsNoPrimary.toStream.dropWhile(x => x.cutoff < random).headOption

    //if we didn't return a revision just return None
    if(cr.isDefined) Some(cr.get.revision) else None
  }
}

object RoutingInfo extends DefaultJsonProtocol {
  implicit val format =
    jsonFormat[Boolean, Seq[ServiceRevision], RoutingInfo](RoutingInfo.apply, "mirrorMode", "revisions")
}

private case class CutoffRevision(cutoff: Double, revision: Int)
