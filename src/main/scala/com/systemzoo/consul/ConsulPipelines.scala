package com.systemzoo.consul

import akka.actor.ActorSystem
import spray.client.pipelining._
import spray.http._
import spray.httpx.UnsuccessfulResponseException
import spray.httpx.unmarshalling._
import scala.concurrent.{ExecutionContextExecutor, Future}
import spray.httpx.SprayJsonSupport._

trait ConsulPipelines {
  val config: ConsulConfig
  implicit val system: ActorSystem
  implicit def ec: ExecutionContextExecutor = system.dispatcher

  def noResponsePipeline: HttpRequest => Future[Int] =
    addHeaders(config.headers) ~> sendReceive ~> checkStatus ~> toStatusCode

  def pipeline[T : FromResponseUnmarshaller]: HttpRequest => Future[T] =
    addHeaders(config.headers) ~> sendReceive ~> unmarshal[T]

  def getKeyPipeline: HttpRequest => Future[Seq[Value]] =
    addHeaders(config.headers) ~> sendReceive ~> wrap404

  def wrap404: HttpResponse => Seq[Value] =
    response =>
      if (response.status.isSuccess)
        response ~> unmarshal[Seq[Value]]
      else if (response.status.intValue == 404) Seq()
      else throw new UnsuccessfulResponseException(response)

  private def checkStatus: HttpResponse => HttpResponse =
    response =>
      if (response.status.isSuccess)
        response
      else throw OperationFailedException(response)

  private def toStatusCode: HttpResponse => Int =
    response => response.status.intValue

}

class OperationFailedException(val status:Int, body:String) extends RuntimeException

object OperationFailedException{
  def apply(r: HttpResponse) = new OperationFailedException(r.status.intValue,if (r.entity.data.length <1024) r.entity.asString else r.entity.data.length + " bytes")
}
