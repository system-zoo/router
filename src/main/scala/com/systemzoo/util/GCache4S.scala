package com.systemzoo.util


import java.util.concurrent.{Callable, TimeUnit}
import akka.actor.ActorRefFactory
import com.google.common.base.Ticker
import com.google.common.cache.{RemovalNotification => GuavaRemovalNotification, _}
import com.google.common.util.concurrent.{ListenableFutureTask, ListenableFuture}
import com.systemzoo.consul.KeyNotFoundException
import com.systemzoo.config.Config
import scala.concurrent.{ExecutionContext, Await, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * Very scala-esque no-frills wrapper around Guava's CacheBuilder, ejecting
 * a Cache (or LoadingCache) when finished
 */

class GCache[K,V] private (builder: CacheBuilder[K, V]) {
  private def updated[NK, NV](builder: CacheBuilder[NK, NV]) = new GCache[NK, NV](builder)

  def maximumSize(max: Long) = updated(builder.maximumSize(max))
  def expireAfterWrite(duration: Duration) = updated(builder.expireAfterWrite(duration.toNanos, TimeUnit.NANOSECONDS))
  def expireAfterAccess(duration: Duration) = updated(builder.expireAfterAccess(duration.toNanos, TimeUnit.NANOSECONDS))
  def refreshAfterWrite(duration: Duration) = updated(builder.refreshAfterWrite(duration.toNanos, TimeUnit.NANOSECONDS))
  def concurrencyLevel(concurrency: Int) = updated(builder.concurrencyLevel(concurrency))
  def initialCapacity(initialCapacity: Int) = updated(builder.initialCapacity(initialCapacity))
  def maximumWeight(weight: Int) = updated(builder.maximumWeight(weight))
  def recordStats() = updated(builder.recordStats())

  def removalListener[NK <: K, NV <: V](removalListener: RemovalNotification[NK, NV] => Unit) =
    updated(builder.removalListener(new RemovalListener[NK, NV] {
      override def onRemoval(notification: GuavaRemovalNotification[NK, NV]): Unit = {
        val rn = RemovalNotification(Option(notification.getKey), Option(notification.getValue), notification.getCause)
        removalListener(rn)
      }
    }))
  def softValues() = updated(builder.softValues())
  def ticker(ticker: Ticker) = updated(builder.ticker(ticker))

  def weakKeys() = updated(builder.weakKeys())
  def weakValues() = updated(builder.weakValues())
  def weigher[NK <: K, NV <: V](weigher: (NK, NV) => Int) = updated[NK, NV](builder.weigher(new Weigher[NK, NV] {
    override def weigh(key: NK, value: NV): Int = weigher(key, value)
  }))

  /**
   * Ejects the built cache
   * @tparam NK
   * @tparam NV
   * @return
   */
  def build[NK <: K, NV <: V]: Cache[NK, NV] = builder.build[NK, NV]

  /**
   * Ejects the build loading cache
   * @param fn
   * @tparam NK
   * @tparam NV
   * @return
   */
  def apply[NK <: K, NV <: V](fn: NK => NV): LoadingCache[NK, NV] =
    builder.build[NK, NV](new CacheLoader[NK, NV] {
      override def load(key: NK): NV = fn(key)
    })

  def loadWith[NK <: K, NV <: V](loader: CacheLoader[NK, NV]): LoadingCache[NK, NV] =
    builder.build[NK, NV](loader)

  override def toString = s"GCache($builder)"

}

case class RemovalNotification[K, V](key: Option[K], value: Option[V], cause: RemovalCause)

object GCache {
  def apply[K, V]() = new GCache[K, V](CacheBuilder.newBuilder().asInstanceOf[CacheBuilder[K,V]])
}

object CacheLoaderConvenience {
  def reloaderFromFutureFunction[K, V](fn: K => Future[V])(implicit arf: ActorRefFactory):
      CacheLoader[K, Future[V]] = new CacheLoader[K, Future[V]] {

    implicit val dispatcher: ExecutionContext = arf.dispatcher

    def load(key: K): Future[V] = fn(key)

    override def reload(key: K, oldValue: Future[V]):
        ListenableFuture[Future[V]] = {
      val task = ListenableFutureTask.create(new Callable[Future[V]] {
        def call(): Future[V] = {
          val futureValue = fn(key)
          Await.ready(futureValue, Config.Router.cacheTTL)
          futureValue.value match {
            case Some(Success(_)) => futureValue
            case Some(Failure(_: KeyNotFoundException)) =>
              // we should evict - the key is not in the cache anymore
              // the refresh will evict, just let this failure through
              futureValue
            case _ => oldValue
          }
        }
      })
      dispatcher.execute(task)
      task
    }
  }
}