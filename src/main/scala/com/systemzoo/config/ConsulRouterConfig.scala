package com.systemzoo.config

import com.systemzoo.consul.{ConsulConfig, ConsulClient}
import com.systemzoo.{RoutingInfoCacheItem, ServiceLocationCacheItem}
import scala.concurrent.Future

/**
 * The "production" version of CachedRouterConfig using consul lookup to resolve
 */
// use this one for production
trait ConsulRouterConfig extends CachedRouterConfig {
  val consulURL: String

  lazy val consulClient = ConsulClient(ConsulConfig(consulURL))

  override def routingInfoLoader(serviceName: String, version:Int): Future[RoutingInfoCacheItem] =
    consulClient.getKey(s"$serviceName-$version").map(RoutingInfoCacheItem.fromJson)

  override def serviceLocationLoader(service: String, version: Int, revision: Int): Future[ServiceLocationCacheItem] =
    consulClient.getService(s"$service-$version-$revision").map { xs =>
      ServiceLocationCacheItem(xs.map(x => s"http://${x.Address}:${x.ServicePort}"))
    }
}
