package com.systemzoo.consul

import akka.actor.ActorSystem
import spray.client.pipelining._
import spray.http.HttpEntity
import scala.concurrent.Future
import spray.httpx.SprayJsonSupport._

class ConsulClient(val config: ConsulConfig)(implicit val system: ActorSystem) extends ConsulPipelines {

  //kv methods
  def deleteKey(key: String): Future[ConsulClient.StatusCode] =
    noResponsePipeline(Delete(config.URLs.keyvalue + key))

  def setKey(key: String, value: String): Future[ConsulClient.StatusCode] =
    noResponsePipeline(Put(config.URLs.keyvalue + key).withEntity(HttpEntity(value)))

  def getKey(key: String): Future[String] = {
    val future = getKeyPipeline(Get(config.URLs.keyvalue + key))
    future.map { values =>
      values.length match {
        case 0 => throw KeyNotFoundException(key)
        case 1 => values.head.decode.Value
        case _ => throw DuplicateKeyException(key) //also shoudln't happen
      }
    }
  }

  //catalog methods
  def getService(service: String): Future[Seq[ServiceNode]] =
    pipeline[Seq[ServiceNode]].apply(Get(config.URLs.service(service)))
}

object ConsulClient {
  type StatusCode = Int
  def apply(config: ConsulConfig)(implicit actorSystem:ActorSystem) = new ConsulClient(config)
}
