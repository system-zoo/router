package com.systemzoo

import spray.can.Http
import akka.io.IO
import akka.actor.ActorSystem
import spray.http.{HttpHeader, HttpHeaders, HttpRequest, Uri}
import spray.routing.{Route, RequestContext}


//Taken from an unmerged pull request:
// https://github.com/bthuillier/spray/blob/proxy-directives/spray-routing/src/main/scala/spray/routing/directives/ProxyDirectives.scala
trait ProxyDirectives {
  
  private def sending(f: RequestContext ⇒ HttpRequest, ctx: RequestContext)(implicit system: ActorSystem): Unit = {
    val transport = IO(Http)(system)
    transport.tell(f(ctx), ctx.responder)
  }

  //added to fix problems with requests appearing to come from wrong host
  def stripHostHeader(headers: List[HttpHeader] = Nil) =
    headers.filterNot(header =>
      header.is(HttpHeaders.Host.lowercaseName) || header.is("X-Service-Revision".toLowerCase) )

  /**
   * proxy the request to the specified uri with the unmatched path
   *
   */
  def proxyToUnmatchedPath(uri: Uri, ctx: RequestContext)(implicit system: ActorSystem): Unit = {

    sending(ctx => ctx.request.copy (
      uri     = uri.withPath(uri.path.++(ctx.unmatchedPath)).
        withQuery(ctx.request.uri.query),
      headers = stripHostHeader(ctx.request.headers)
    ), ctx)
  }
}

object ProxyDirectives extends ProxyDirectives
