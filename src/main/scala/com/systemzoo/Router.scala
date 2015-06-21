package com.systemzoo

import akka.actor.ActorDSL._
import akka.actor._
import akka.io.IO
import akka.io.Tcp.Bound
import com.google.common.cache.LoadingCache
import com.systemzoo.config.{ConsulRouterConfig, Config, RouterConfig}
import com.systemzoo.consul.KeyNotFoundException
import com.systemzoo.util.RefreshPolicy
import com.systemzoo.util.GCache
import spray.can.Http
import spray.http._
import spray.routing._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.Future
import scala.util.{Success, Failure}

object Router {

  type VersionGCache = LoadingCache[String, Future[RoutingInfoCacheItem]]
  type ServiceGCache = LoadingCache[String, Future[ServiceLocationCacheItem]]

  val serviceName = "router"

  implicit val system = ActorSystem(serviceName + "-system")
  implicit val dispatcher = system.dispatcher

  def standardRefreshPolicy[T]: RefreshPolicy[T] = RefreshPolicy[T] {
    case Failure(exception: KeyNotFoundException) => RefreshPolicy.Evict
    case Failure(exception: RuntimeException)     => RefreshPolicy.Evict
    case Failure(exception)                       => RefreshPolicy.Refresh
    case Success(item)                            => RefreshPolicy.Refresh
  }

  // inject the necessary production config
  implicit object MainRouterConfig$ConsulRouter$ extends ConsulRouterConfig {
    val consulURL: String  = Config.Consul.url

    val refresh: Option[FiniteDuration] = Some(Config.Router.cacheTTL)

    val actorSystem: ActorSystem = system

    val routingInfoCacheBuilder: GCache[(String, Int), Future[RoutingInfoCacheItem]] = GCache().
      maximumSize(Config.Router.maxCacheSize).
      refreshAfterWrite(Config.Router.cacheTTL)

    val serviceLocationCacheBuilder: GCache[(String, Int, Int), Future[ServiceLocationCacheItem]] = GCache().
      maximumSize(Config.Router.maxCacheSize).
      refreshAfterWrite(Config.Router.cacheTTL)

    val refreshPolicyServiceLocation: RefreshPolicy[ServiceLocationCacheItem] = standardRefreshPolicy
    val refreshPolicyRoutingInfo: RefreshPolicy[RoutingInfoCacheItem] = standardRefreshPolicy
  }

  def main(args: Array[String]): Unit = {
    val router = new Router
    router.run(args)
  }
}


class Router(implicit routerConfig: RouterConfig) {

  def run(args:Array[String]) {

    implicit val arf = routerConfig.actorSystem
    val service = arf.actorOf(Props(new RouterActor))

    val ioListener = actor("ioListener")(new Act with ActorLogging {
      become {
        case b@Bound(connection) => log.info(b.toString)
      }
    })

    IO(Http).tell(
      Http.Bind(service, "::0", 80),
      ioListener
    )
  }
}

class RouterActor(implicit routerConfig: RouterConfig) extends HttpServiceActor {

  val routerService = new RouterService

  implicit val system = context.system

  def receive = handleTimeouts orElse runRoute(routerService.route)

  def handleTimeouts: Receive = {
    case Timedout(x: HttpRequest) =>
      sender ! HttpResponse(StatusCodes.InternalServerError, "The router has timed out your request.")
  }
}
