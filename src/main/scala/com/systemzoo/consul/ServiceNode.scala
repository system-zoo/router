package com.systemzoo.consul

import spray.json.{NullOptions, DefaultJsonProtocol}

case class ServiceNode(
                        Node           : String,
                        Address        : String,
                        ServiceID      : String,
                        ServiceName    : String,
                        ServiceTags    : Option[Seq[String]],
                        ServicePort    : Int
                        )
object ServiceNode extends DefaultJsonProtocol with NullOptions {
  implicit val format = jsonFormat6(ServiceNode.apply)
}