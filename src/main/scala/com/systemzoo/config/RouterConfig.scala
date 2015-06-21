package com.systemzoo.config

import akka.actor.ActorSystem
import com.systemzoo.consul.KeyNotFoundException
import com.systemzoo._
import com.systemzoo.util.{RefreshPolicy, CacheAutoRefresh, GCache}
import scala.concurrent.{ExecutionContext, Future}
import com.systemzoo.util.CacheLoaderConvenience._
import scala.concurrent.duration.FiniteDuration

/**
 * Parfait Router config
 */
// use this one for fakes
trait RouterConfig {
  implicit val actorSystem: ActorSystem
  val cacheLookup: CacheLookup
}

trait CachedRouterConfig extends RouterConfig {
  val routingInfoCacheBuilder:      GCache[(String,Int), Future[RoutingInfoCacheItem]]
  val serviceLocationCacheBuilder:  GCache[(String, Int, Int), Future[ServiceLocationCacheItem]]

  val refresh: Option[FiniteDuration]
  val refreshPolicyServiceLocation: RefreshPolicy[ServiceLocationCacheItem]
  val refreshPolicyRoutingInfo:     RefreshPolicy[RoutingInfoCacheItem]

  protected implicit lazy val ec: ExecutionContext = actorSystem.dispatcher

  def routingInfoLoader(serviceName: String, version: Int): Future[RoutingInfoCacheItem]
  def serviceLocationLoader(serviceName: String, version: Int, revision: Int): Future[ServiceLocationCacheItem]

  lazy val routingInfoCache = {
    val rCache = routingInfoCacheBuilder.loadWith[(String,Int), Future[RoutingInfoCacheItem]](
      reloaderFromFutureFunction((routingInfoLoader _).tupled))

    if (refresh.isDefined) CacheAutoRefresh.on(rCache, refresh.get, refreshPolicyRoutingInfo)
    rCache
  }

  lazy val serviceLocationCache = {
    val sCache = serviceLocationCacheBuilder.loadWith[(String, Int, Int), Future[ServiceLocationCacheItem]](
      reloaderFromFutureFunction((serviceLocationLoader _).tupled))

    if (refresh.isDefined) CacheAutoRefresh.on(sCache, refresh.get, refreshPolicyServiceLocation)
    sCache
  }

  lazy val cacheLookup = new CacheLookup {
    def lookupServiceLocation(serviceName: String, version: Int, revision: Int): Future[ServiceLocationCacheItem] =
      serviceLocationCache.get((serviceName, version, revision)).recover {
        case e: KeyNotFoundException => throw ServiceNotFoundException(serviceName, version, revision)
      }
    def invalidateServiceLocation(serviceName: String, version: Int, revision: Int): Unit =
      serviceLocationCache.invalidate((serviceName, version, revision))

    def lookupRoutingInfo(serviceName: String, version: Int): Future[RoutingInfoCacheItem] =
      routingInfoCache.get((serviceName, version)).recover {
        case e: KeyNotFoundException => throw RoutingInfoNotFoundException(serviceName, version)
      }
    def invalidateRoutingInfo(serviceName: String, version:Int): Unit =
      routingInfoCache.invalidate((serviceName, version))
  }
}
