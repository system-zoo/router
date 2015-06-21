package com.systemzoo.consul

import spray.http.HttpHeaders.RawHeader

case class ConsulConfig(urlBase: String, headers: List[RawHeader] = List.empty) {
  object URLs{
    private val base = s"$urlBase/v1"
    val keyvalue      = s"$base/kv/"
    def service(name: String) = s"$base/catalog/service/$name"
  }

}