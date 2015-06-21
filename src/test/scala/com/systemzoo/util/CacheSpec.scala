package com.systemzoo.util

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.systemzoo.CacheLookup
import com.systemzoo.config.RouterConfig
import org.scalatest._
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Milliseconds, Seconds, Span}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Success
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Test some stuff with the cache and auto-refresh
 */
class CacheSpec(_system: ActorSystem) extends TestKit(_system) with FunSpecLike
    with ShouldMatchers with BeforeAndAfterAll with Eventually {

  def this() = this(ActorSystem("CacheSpec"))

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(_system)
  }

  import CacheLoaderConvenience._

  describe("A refreshing cache") {
    var countMap = Map.empty[Int, Int]

    def calc(x: Int): Future[Int] = Future {
      val existing = countMap.get(x).getOrElse(0)
      countMap += x -> (existing + 1)
      Thread.sleep(200)
      x * x
    }

    it ("should refresh on a periodic schedule, whilst not blocking at all") {
      implicit val patienceConfig: PatienceConfig = PatienceConfig(Span(10, Seconds), Span(10, Milliseconds))

      implicit object CacheTestRouterConfig extends RouterConfig {
        override implicit val actorSystem: ActorSystem = _system  // this is needed for futures
        override lazy val cacheLookup: CacheLookup = ???  // shouldn't need this, if we do, it's a bug
      }

      val squareCache = GCache().maximumSize(10).loadWith(reloaderFromFutureFunction(calc))

      countMap should be (empty)

      CacheAutoRefresh.on(squareCache, 250.milliseconds, RefreshPolicy[Int] {
        case _ => RefreshPolicy.Refresh   // just refresh everything for this test
      })

      countMap should be (empty)

      val sq2 = squareCache.get(2)
      sq2.value should be (None)
      Thread.sleep(110)
      sq2.value should be (None)

      countMap.size should be (1)
      countMap.get(2) should be (Some(1))

      Thread.sleep(160)
      squareCache.get(2).value should be (Some(Success(4)))

      countMap.size should be (1)
      eventually {
        countMap.get(2) should be(Some(2))
      }

      squareCache.get(2).value should be (Some(Success(4)))
      countMap.get(2) should be (Some(2))


      for (i <- 1 to 100) {
        squareCache.get(2).value should be (Some(Success(4)))
        Thread.sleep(10)
      }

      countMap.get(2).get should be < (8)


      // make sure the cache is continually refreshed even when not being used
      eventually {
        countMap.get(2).get should be >= (8)
      }

    }
  }
}
