package com.systemzoo

case class ServiceNotFoundException(service: String, version: Int, revision: Int)
  extends Exception(s"Unable to find service: $service version: $version revision: $revision")

case class RoutingInfoNotFoundException(service: String, version: Int)
  extends Exception(s"Unable to find routing information for service: $service version: $version")
