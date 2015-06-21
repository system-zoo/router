package com.systemzoo

import scala.concurrent.Future

/**
 * Look up a service and revision
 * Easy point at which to swap out a real implementation for a testing fake
 */
trait CacheLookup {
  def lookupServiceLocation(    serviceName: String, version: Int, revision: Int): Future[ServiceLocationCacheItem]
  def invalidateServiceLocation(serviceName: String, version: Int, revision: Int): Unit
  
  def lookupRoutingInfo(    serviceName: String, version: Int): Future[RoutingInfoCacheItem]
  def invalidateRoutingInfo(serviceName: String, version: Int): Unit
}
