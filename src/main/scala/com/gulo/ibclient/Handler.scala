package com.gulo.ibclient.handler

trait Handler {
  def error(throwable: Throwable): Unit = {}
}
