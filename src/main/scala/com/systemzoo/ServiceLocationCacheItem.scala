package com.systemzoo

import scala.util.Random

case class ServiceLocationCacheItem(uris: Seq[String]) {
  def randomURI = uris(Random.nextInt(uris.length))
}
