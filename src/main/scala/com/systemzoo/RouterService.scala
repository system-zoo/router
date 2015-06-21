package com.systemzoo

import akka.actor.{ActorRefFactory, ActorSystem}
import com.systemzoo.config.RouterConfig
import spray.http.StatusCodes._
import spray.http.{HttpResponse, HttpRequest, Uri}
import spray.routing._
import scala.concurrent.Future
import scala.util.{Failure, Success}
import spray.client.pipelining._

/**
 * The meat and potatoes router service
 */
class RouterService(implicit routerConfig: RouterConfig) extends HttpService with ProxyDirectives {
  implicit def actorRefFactory: ActorRefFactory = routerConfig.actorSystem
  implicit def actorSystem: ActorSystem = routerConfig.actorSystem
  implicit def executionContext = actorRefFactory.dispatcher

  val cache = routerConfig.cacheLookup

  def route(implicit system: ActorSystem) = {
    pathPrefix(Segment / "v" ~ IntNumber ) { (serviceName, version) =>
      optionalHeaderValueByName("X-Service-Revision") { optRevision =>
        detach() { ctx =>
          if (optRevision.isDefined) {
            val uri = getURI(serviceName, version, optRevision.get.toInt)
            onCompleteOfURI(uri, ctx)
          } else {
            val routingInfoFuture = cache.lookupRoutingInfo(serviceName, version)

            val uri = routingInfoFuture.flatMap { routingInfo =>
              if (routingInfo.info.mirrorMode) {
                val alternateRevision = routingInfo.info.getAlternateRevision
                if (alternateRevision.isDefined) {
                  val alternateURI = getURI(serviceName, version, alternateRevision.get)

                  onCompleteOfAlternateURI(alternateURI, ctx)
                }

                val primaryURI = getURI(serviceName, version, routingInfo.info.primaryRevision)
                primaryURI
              } else {
                val revision = routingInfo.info.getRevision
                val uri = getURI(serviceName, version, revision)
                uri
              }
            }

            onCompleteOfURI(uri, ctx)
          }
        }
      }
    }
  }

  def getURI(serviceName: String, version: Int, revision: Int): Future[String] = {
    val cacheItem = cache.lookupServiceLocation(serviceName, version, revision)

    cacheItem.onFailure {
      case ex: ServiceNotFoundException => cache.invalidateServiceLocation(ex.service, ex.version, ex.revision)
    }

    cacheItem.flatMap { uris =>
      if(uris.uris.isEmpty) throw {
        // evict from the cache
        cache.invalidateServiceLocation(serviceName, version, revision)
        ServiceNotFoundException(serviceName, version, revision)
      }
      Future.successful(uris.randomURI)
    }
  }

  def onCompleteOfURI(uri: Future[String], ctx: RequestContext): Unit = {
    uri.onComplete {
      case Success(u) =>
        proxyToUnmatchedPath(Uri(u), ctx)
      case Failure(e: ServiceNotFoundException) =>
        ctx.complete(NotFound, e.getMessage)
      case Failure(e: RoutingInfoNotFoundException) =>
        ctx.complete(NotFound, e.getMessage)
      case Failure(ex) =>
        ctx.complete(InternalServerError, s"An error occurred: ${ex.getMessage}")
    }
  }

  def onCompleteOfAlternateURI(uriFuture: Future[String], ctx: RequestContext): Unit = {
    val pipeline: HttpRequest => Future[HttpResponse] = sendReceive

    uriFuture.map{ uri =>
      val request = ctx.request.copy(
        uri = Uri(uri).withPath(Uri(uri).path.++(ctx.unmatchedPath)).
          withQuery(ctx.request.uri.query),
        headers = stripHostHeader(ctx.request.headers)
      )

      pipeline(request)
    }
  }
}
