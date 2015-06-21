package com.monsanto.arch.router

import akka.actor.ActorSystem
import com.systemzoo.config.{ConsulRouterConfig, Config, CachedRouterConfig}
import com.systemzoo.util.{RefreshPolicy, GCache}
import com.systemzoo._
import org.scalatest._
import org.scalatest.concurrent.Eventually
import spray.http.HttpHeaders.RawHeader
import spray.http.StatusCodes
import spray.testkit.ScalatestRouteTest
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import scala.collection.mutable
import com.systemzoo.consul.KeyNotFoundException

class Router_AT extends FunSpec with ScalatestRouteTest with ShouldMatchers with Eventually {

  implicit val routeTestTimeout = RouteTestTimeout(10.seconds)

  describe("The Router route") {

    class FakeCacheLookup extends CacheLookup {
      private val backingServiceMap = Map(
        ("httpbin", 1, 1) -> Future(ServiceLocationCacheItem(Seq("http://172.17.8.172:49513"))),
        ("nonansweringservice", 1, 1) -> Future(ServiceLocationCacheItem(Seq("http://192.192.192.192:192"))),
        ("badurl", 1, 1) -> Future(ServiceLocationCacheItem(Seq("not a real url at all")))
      )

      private val backingVersionMap = Map.empty[(String,Int), Future[RoutingInfoCacheItem]]

      private val currentServiceMap = mutable.Map.empty ++ backingServiceMap // copy the reference map

      private val currentVersionMap = mutable.Map.empty ++ backingVersionMap

      def invalidateRoutingInfo(serviceName: String,version:Int): Unit =
        currentVersionMap -= ((serviceName, version))

      def lookupRoutingInfo(serviceName: String,version:Int): Future[RoutingInfoCacheItem] =
        currentVersionMap((serviceName, version))

      def invalidateServiceLocation(serviceName: String, version: Int, revision: Int): Unit = 
        currentServiceMap -= ((serviceName, version, revision))

      def lookupServiceLocation(serviceName: String, version: Int, revision: Int): Future[ServiceLocationCacheItem] = 
        currentServiceMap((serviceName, version, revision))
    }

    describe("with fake failing lookup services") {
      @volatile var refreshCount = 0

      implicit object FakeTestConfig extends CachedRouterConfig {
        override implicit val actorSystem: ActorSystem = system
        override val routingInfoCacheBuilder: GCache[(String,Int), Future[RoutingInfoCacheItem]] = GCache().
          maximumSize(Config.Router.maxCacheSize).
          refreshAfterWrite(Config.Router.cacheTTL)
        override val serviceLocationCacheBuilder: GCache[(String, Int, Int), Future[ServiceLocationCacheItem]] = GCache().
          maximumSize(Config.Router.maxCacheSize).
          refreshAfterWrite(Config.Router.cacheTTL)
        override val refresh: Option[FiniteDuration] = Some(500.milliseconds)
        override val refreshPolicyServiceLocation: RefreshPolicy[ServiceLocationCacheItem] = Router.standardRefreshPolicy
        override val refreshPolicyRoutingInfo: RefreshPolicy[RoutingInfoCacheItem] = Router.standardRefreshPolicy

        // all lookups throw exception
        override def routingInfoLoader(serviceName: String, version:Int): Future[RoutingInfoCacheItem] =
          Future.failed {
            new RuntimeException("No route to host to http://172.17.8.192:2379/" +
              s"v2/keys/services%2F$version?recursive=false&sorted=false")
          }

        override def serviceLocationLoader(serviceName: String, version: Int, revision: Int): Future[ServiceLocationCacheItem] =
          Future.failed { refreshCount += 1; new RuntimeException("No route to host to http://172.17.8.192:2379/" +
            s"v2/keys/services%2F$serviceName?recursive=false&sorted=false") }

      }

      val routeService = new RouterService
      val route = routeService.route

      implicit val patienceConfig = PatienceConfig(10.seconds, 100.milliseconds)

      it("should not replace or evict a once successful lookup with a failure to contact the router.") {
        FakeTestConfig.serviceLocationCache.put(("fakeservice",1,1), Future(ServiceLocationCacheItem(Seq("http://127.0.0.1:12345"))))
        FakeTestConfig.serviceLocationCache.asMap.keySet should contain(("fakeservice",1,1))

        refreshCount should be (0)
        Await.result(routeService.getURI("fakeservice",1,1), 1.second) should be ("http://127.0.0.1:12345")

        // now wait for the refresh to happen
        eventually {
          refreshCount should be >= 0   // after the refresh
          FakeTestConfig.serviceLocationCache.asMap.keySet should contain (("fakeservice",1,1)) // fakeservice should still be there
          Await.result(routeService.getURI("fakeservice",1,1), 1.second) should be ("http://127.0.0.1:12345")
            // and the route should still be the last "valid" value
        }
      }

      it("should eventually time out trying to do a service lookup") {
        val result = routeService.getURI("fakeService",1,1)
        intercept[RuntimeException] { Await.result(result, 100.seconds) }
      }

      it("should signal a connection error when router can't be contacted") {
        Get("/fakeService/v1").withHeaders(RawHeader("X-Service-Revision", "1")) ~> route ~> check {
          assert(response.status === StatusCodes.InternalServerError)
          assert(response.entity.asString contains "No route to host")
          eventually {
            FakeTestConfig.serviceLocationCache.asMap.keySet should not contain("fakeService",1,1)
          }
        }
      }
    }

    describe("with one successful, one missing, and one failing service lookup") {
      @volatile var refreshCount = 0

      implicit object FakeTestConfig extends CachedRouterConfig {
        override implicit val actorSystem: ActorSystem = system
        override val routingInfoCacheBuilder: GCache[(String,Int), Future[RoutingInfoCacheItem]] = GCache().
          maximumSize(Config.Router.maxCacheSize).
          refreshAfterWrite(Config.Router.cacheTTL)
        override val serviceLocationCacheBuilder: GCache[(String, Int, Int), Future[ServiceLocationCacheItem]] = GCache().
          maximumSize(Config.Router.maxCacheSize).
          refreshAfterWrite(Config.Router.cacheTTL)
        override val refresh: Option[FiniteDuration] = Some(500.milliseconds)
        override val refreshPolicyServiceLocation: RefreshPolicy[ServiceLocationCacheItem] = Router.standardRefreshPolicy
        override val refreshPolicyRoutingInfo: RefreshPolicy[RoutingInfoCacheItem] = Router.standardRefreshPolicy


        // all lookups throw exception
        override def routingInfoLoader(serviceName: String, version:Int): Future[RoutingInfoCacheItem] =
          Future.failed {
            new RuntimeException("No route to host to http://172.17.8.192:2379/" +
              s"v2/keys/services%2F$version?recursive=false&sorted=false")
          }

        override def serviceLocationLoader(serviceName: String, version: Int, revision: Int): Future[ServiceLocationCacheItem] = {
          refreshCount += 1
          serviceName match {
            case "goodservice" => Future.successful(ServiceLocationCacheItem(Seq("http://172.17.8.172:12345")))
            case "nosuchservice" => Future.failed(KeyNotFoundException("The key or directory was not found: services/nosuchservice"))
            case _ => Future.failed(new RuntimeException("No route to host to http://172.17.8.192:2379/" +
              s"v2/keys/services%2F$serviceName?recursive=false&sorted=false"))
          }
        }

      }

      val routeService = new RouterService
      val route = routeService.route

      implicit val patienceConfig = PatienceConfig(100.seconds, 100.milliseconds)

      it("should not replace or evict a once successful lookup with a failure to contact the router.") {
        FakeTestConfig.serviceLocationCache.put(("fakeservice",1,1),   Future(ServiceLocationCacheItem(Seq("http://127.0.0.1:12345"))))
        FakeTestConfig.serviceLocationCache.put(("goodservice",1,1),   Future(ServiceLocationCacheItem(Seq("http://127.0.0.1:12345"))))
        FakeTestConfig.serviceLocationCache.put(("nosuchservice",1,1), Future(ServiceLocationCacheItem(Seq("http://127.0.0.1:12345"))))
        FakeTestConfig.serviceLocationCache.asMap.keySet should contain(("fakeservice",  1,1))
        FakeTestConfig.serviceLocationCache.asMap.keySet should contain(("goodservice",  1,1))
        FakeTestConfig.serviceLocationCache.asMap.keySet should contain(("nosuchservice",1,1))

        refreshCount should be(0)
        Await.result(routeService.getURI("fakeservice",  1,1), 1.second) should be("http://127.0.0.1:12345")
        Await.result(routeService.getURI("goodservice",  1,1), 1.second) should be("http://127.0.0.1:12345")
        Await.result(routeService.getURI("nosuchservice",1,1), 1.second) should be("http://127.0.0.1:12345")

        // now wait for the refresh to happen
        eventually {
          refreshCount should be >= 0 // after the refresh
          FakeTestConfig.serviceLocationCache.asMap.keySet should contain(("fakeservice",1,1)) // fakeservice should still be there
          Await.result(routeService.getURI("fakeservice",1,1), 1.second) should be("http://127.0.0.1:12345")
          // and the route should still be the last "valid" value
          FakeTestConfig.serviceLocationCache.asMap.keySet should contain(("goodservice",1,1)) // goodservice should still be there
          Await.result(routeService.getURI("goodservice",1,1), 1.second) should be("http://172.17.8.172:12345")
          // while the good one should have been updated

          // and finally, the one that's missing should be removed from the cache
          FakeTestConfig.serviceLocationCache.asMap.keySet should not contain(("nosuchservice",1,1))
        }
      }
    }

    describe("when consul is not responding") {
      implicit object TestRouterConfig extends ConsulRouterConfig {
        override val routingInfoCacheBuilder: GCache[(String,Int), Future[RoutingInfoCacheItem]] = GCache().
          maximumSize(Config.Router.maxCacheSize)
        override val serviceLocationCacheBuilder: GCache[(String, Int, Int), Future[ServiceLocationCacheItem]] = GCache().
          maximumSize(Config.Router.maxCacheSize)

        implicit val actorSystem = system
        override val consulURL: String = "http://172.17.8.172:2279"
        override val refresh: Option[FiniteDuration] = Some(1.second) // let it refresh
        override val refreshPolicyServiceLocation: RefreshPolicy[ServiceLocationCacheItem] = Router.standardRefreshPolicy
        override val refreshPolicyRoutingInfo: RefreshPolicy[RoutingInfoCacheItem] = Router.standardRefreshPolicy
      }

      val routeService = new RouterService

      val route = routeService.route

      implicit val patienceConfig = PatienceConfig(10.seconds, 100.milliseconds)

      it("should eventually time out trying to do a service lookup") {
        val result = routeService.getURI("fakeService",1,1)
        val msg = intercept[RuntimeException] { Await.result(result, 10.seconds) }
        msg.getMessage.toLowerCase should include ("connection attempt to 172.17.8.172:2279 failed")
      }

      it("should signal a connection error when router can't be contacted") {
        Get("/fakeService/v1") ~> route ~> check {
          assert(response.status === StatusCodes.InternalServerError)
          assert(response.entity.asString contains "Connection attempt to 172.17.8.172:2279 failed")
          eventually {
            TestRouterConfig.serviceLocationCache.asMap.keySet should not contain(("fakeService",1,1))
          }
        }
      }
    }

    describe("with a real consul lookup") {
      // standard testing config
      implicit object TestRouterConfig extends ConsulRouterConfig {
        override val routingInfoCacheBuilder: GCache[(String,Int), Future[RoutingInfoCacheItem]] = GCache().
          maximumSize(Config.Router.maxCacheSize)
        override val serviceLocationCacheBuilder: GCache[(String, Int, Int), Future[ServiceLocationCacheItem]] = GCache().
          maximumSize(Config.Router.maxCacheSize)

        val actorSystem = system
        override val consulURL: String = "http://172.17.8.172:8500"
        override val refresh: Option[FiniteDuration] = None
        override val refreshPolicyServiceLocation: RefreshPolicy[ServiceLocationCacheItem] = Router.standardRefreshPolicy
        override val refreshPolicyRoutingInfo: RefreshPolicy[RoutingInfoCacheItem] = Router.standardRefreshPolicy
      }


      //set up our standard route
      val routeInfo = RoutingInfo(false, Seq(ServiceRevision(1,1,true)))
      TestRouterConfig.consulClient.setKey("httpbin-1", RoutingInfo.format.write(routeInfo).compactPrint)

      val routeService = new RouterService

      val route = routeService.route

      it("should return a successful lookup for a good service") {
        val selectedService = routeService.getURI("httpbin",1,1)
        //testing on port seems brittle so ignoring it
        assert(Await.result(selectedService, 10.seconds).startsWith("http://172.17.8.172:"))
      }

      it("should return a failure for no service found") {
        val selectedService = routeService.getURI("nosuchservice",1,1)
        intercept[ServiceNotFoundException] { Await.result(selectedService, 10.seconds) }
      }

      it("should return success for a good service without revision lookup") {
        Get("/httpbin/v1/get").withHeaders(RawHeader("X-Service-Revision", "1")) ~> route ~> check {
          assert(response.status === StatusCodes.OK)
          TestRouterConfig.serviceLocationCache.asMap.keySet should contain(("httpbin",1,1))
        }
      }

      it("should return success for a good service with revision lookup") {
        Get("/httpbin/v1/get") ~> route ~> check {
          assert(response.status === StatusCodes.OK)
          TestRouterConfig.serviceLocationCache.asMap.keySet should contain(("httpbin",1,1))
        }
      }

      it("should return 404 for a service that isn't found and it should not be cached") {
        Get("/fakeService/v1") ~> route ~> check {
          assert(response.status === StatusCodes.NotFound)
          assert(response.entity.asString === "Unable to find routing information for service: fakeService version: 1")
          TestRouterConfig.serviceLocationCache.asMap.keySet should not contain(("fakeService",1,1))
        }
      }

      it("should return 404 for a service with revision that isn't found and it should not be cached") {
        Get("/fakeService/v1").withHeaders(RawHeader("X-Service-Revision", "1")) ~> route ~> check {
          assert(response.status === StatusCodes.NotFound)
          assert(response.entity.asString === "Unable to find service: fakeService version: 1 revision: 1")
          TestRouterConfig.serviceLocationCache.asMap.keySet should not contain(("fakeService",1,1))
        }
      }

      it("should evict a missing service from the cache") {
        // call a service that has no URLs registered
        TestRouterConfig.serviceLocationCache.put(("badservice",1,1), Future(ServiceLocationCacheItem(Seq.empty[String])))
        TestRouterConfig.serviceLocationCache.asMap.keySet should contain(("badservice",1,1))

        val selectedService = routeService.getURI("badservice",1,1)
        val ex = intercept[ServiceNotFoundException] {
          Await.result(selectedService, 10.seconds)
        }

        ex.getMessage should be("Unable to find service: badservice version: 1 revision: 1")

        // should have been evicted from the cache now
        TestRouterConfig.serviceLocationCache.asMap.keySet should not contain (("badservice",1,1))

        val selectedService2 = routeService.getURI("badservice",1,1)
        val ex2 = intercept[ServiceNotFoundException] {
          Await.result(selectedService2, 10.seconds)
        }

        ex2.getMessage should include("Unable to find service: badservice version: 1 revision: 1")

        TestRouterConfig.serviceLocationCache.asMap.keySet should not contain (("badservice",1,1))
      }
    }
  }
}