package com.gulo.ibclient

import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

import com.ib.client.{EWrapper, EClientSocket}
import com.ib.client.EJavaSignal
import com.gulo.ibclient.handler.Handler

abstract class IBClient(
  val host: String,
  val port: Int,
  val clientId: Int
) extends EWrapper {

  val eReaderSignal = new EJavaSignal
  val eClientSocket = new EClientSocket(this, eReaderSignal)

  var reqId: Int = 0
  var orderId: Int = 0

  var errorCount: Int = 0
  var warnCount: Int = 0

  private[this] var connectResult = Promise[IBClient]()
  /**
   * A map of reqId => Promise
   * Each request with its id is associated with a Handler
   * and a promise to fulfill when the data is ready for consumption
   * by the client.
   */
  val reqHandler = mutable.Map.empty[Int, Handler]
  val reqPromise = mutable.Map.empty[Int, AnyRef]
  
  // TODO: Specific handlers and promises for calls that don't have an associated id
   
}
