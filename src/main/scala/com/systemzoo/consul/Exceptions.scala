package com.systemzoo.consul

case class KeyNotFoundException( key: String) extends Exception
case class DuplicateKeyException(key: String) extends Exception
