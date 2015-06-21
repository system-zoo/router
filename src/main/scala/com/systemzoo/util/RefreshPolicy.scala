package com.systemzoo.util

import scala.util.Try

/**
 * A cache auto-refresh policy that, based on the completion of a Future[V], decides upon refresh
 * whether the value should be refreshed, evicted or skipped
 */
class RefreshPolicy[V](decider: PartialFunction[Try[V], RefreshPolicy.RefreshRequest]) {
  def decide(v: Try[V]): RefreshPolicy.RefreshRequest = if (decider.isDefinedAt(v)) decider(v) else RefreshPolicy.Skip
}


object RefreshPolicy {
  def apply[V](decider: PartialFunction[Try[V], RefreshPolicy.RefreshRequest]) = new RefreshPolicy[V](decider)

  sealed abstract class RefreshRequest
  case object Refresh extends RefreshRequest
  case object Evict extends RefreshRequest
  case object Skip extends RefreshRequest

  def skipAll[T] = RefreshPolicy[T] { case _ => Skip } // convenient do-nothing option for testing, etc.
}