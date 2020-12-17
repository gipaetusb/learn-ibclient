package com.gulo.ibclient.util

import scala.jdk.CollectionConverters._
import com.google.common.collect.TreeMultimap
import java.util.Calendar
import org.slf4j.{Logger, LoggerFactory}


/*
 * Stateful utility to account for historical requests in order no to violate historical data limitations as specified in:

 * https://interactivebrokers.github.io/tws-api/historical_limitations.html
 * The basic usage is done through the following calls:
 * Requests should be accounted for with the "requested" method
 * nextRequestAfter_ms gives us the wait time until the next request can be made
 * cleanup will free the accounting of old requests, should be called periodically
 * */

class HistoricalRateLimiter {
  private[this] val log: Logger = LoggerFactory.getLogger(this.getClass)
  
  // TODO: can we use a scala collection here?
  private[this] val requests = TreeMultimap.create[Long, HistoricalRequest](
    implicitly[Ordering[Long]].reverse,
    implicitly[Ordering[HistoricalRequest]]
  )

  def now_ms: Long = Calendar.getInstance().getTimeInMillis()

  def requested(request: HistoricalRequest, reftime_ms: Option[Long] = None): Unit = {
    var curTime = reftime_ms.getOrElse(now_ms)
    while (requests.put(curTime, request) == false) {
      log.warn(s"Incrememtomg reftime of request ${request}, another request with the same time ${curTime}")
      curTime += 1
    }
  }

  protected def latestInLast(
    timeframe_ms: Long,
    reftime_ms: Long = now_ms
  ): Iterator[java.util.Map.Entry[Long, HistoricalRequest]] = {
    requests.entries.iterator.asScala.takeWhile { 
      x => x.getKey > reftime_ms - timeframe_ms
    }
  }
}
