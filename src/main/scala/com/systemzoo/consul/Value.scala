package com.systemzoo.consul

import org.apache.commons.codec.binary.Base64
import spray.json.{RootJsonFormat, DefaultJsonProtocol}

case class Value(
                  CreateIndex       : Int,
                  ModifyIndex       : Int,
                  LockIndex         : Int,
                  Key               : String,
                  Flags             : Int,
                  Value             : String
                  ) {
  def decode = this.copy(Value = new String(Base64.decodeBase64(this.Value.getBytes)))
}
object Value extends DefaultJsonProtocol {
  implicit val format: RootJsonFormat[Value] = jsonFormat6(Value.apply)
}
