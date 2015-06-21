package com.systemzoo.util

import akka.actor._
import akka.event.Logging
import com.google.common.cache.{LoadingCache, Cache}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.collection.JavaConverters._
import scala.util.Try

/**
 * Set up an actor to repeatedly refresh a guava loading cache on a given schedule. Use with
 * the async reloader to keep a constantly working and up to date cache.
 */
class CacheAutoRefresh[K, V](cache: LoadingCache[K, Future[V]], duration: FiniteDuration, refreshPolicy: RefreshPolicy[V]) extends Actor {
  import context._
  val log = Logging(context.system, this)
  case object Tick

  override def preStart() =
    system.scheduler.scheduleOnce(duration, self, Tick)

  // override postRestart so we don't call preStart and schedule a new message
  override def postRestart(reason: Throwable) = {}

  def receive = {
    case Tick =>
      // send another periodic tick after the specified delay
      system.scheduler.scheduleOnce(duration, self, Tick)
      // Refresh the cache
      val refreshResult = Try {
        for {
          (key, value) <- cache.asMap().asScala
          completedValue <- value.value
        } {
          refreshPolicy.decide(completedValue) match {
            case RefreshPolicy.Refresh =>
              log.info(s"Refreshing $key with future value: $completedValue")
              cache.refresh(key)
            case RefreshPolicy.Evict   => cache.invalidate(key)
              log.warning(s"Evicting $key with future value: $completedValue")
            case _ =>
              log.info(s"Skipping refresh of $key with future value $completedValue")
          }
        }
      }
      refreshResult.transform({ success =>
        Try(log.info("Refreshed cache {}", cache.toString))
      }, { failure =>
        Try(log.error(failure.getCause, "Failed to refresh cache {}", cache.toString))
      })
  }
}

object CacheAutoRefresh {
  def props[K, V](cache: LoadingCache[K, Future[V]], duration: FiniteDuration, refreshPolicy: RefreshPolicy[V]) =
    Props(new CacheAutoRefresh[K, V](cache, duration, refreshPolicy))

  def on[K, V](cache: LoadingCache[K, Future[V]], duration: FiniteDuration, refreshPolicy: RefreshPolicy[V])
              (implicit arf: ActorRefFactory): ActorRef =
    arf.actorOf(props(cache, duration, refreshPolicy))
}


