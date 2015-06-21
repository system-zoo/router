package com.systemzoo.config

import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._

object Config {
  private val masterConfig    = ConfigFactory.load()

  object Consul {
    private val consul        = masterConfig.getConfig("consul")
    lazy val url              = consul.getString("url")
    lazy val timeout          = consul.getInt("timeout").seconds
  }

  object Router {
    private val router        = masterConfig.getConfig("router")
    lazy val cacheTTL         = router.getInt("cacheTTL").seconds
    lazy val maxCacheSize     = router.getLong("maxCacheSize")
  }
}
