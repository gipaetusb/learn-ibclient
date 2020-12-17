package com.gulo.ibclient.util

import scala.math
import scala.jdk.CollectionConverters._
import com.google.common.collect.TreeMultimap
import java.util.Calendar
import org.slf4j.{Logger, LoggerFactory}


/*
 * Stateful utility to account for historical requests in order
 * not to violate historical data limitations as specified in:
 * https://interactivebrokers.github.io/tws-api/historical_limitations.html
 * The basic usage is done through the following calls:
 *   Requests should be accounted for with the "requested" method
 *   nextRequestAfter_ms gives us the wait time until the next request can be made
 *   cleanup will free the accounting of old requests, should be called periodically
 */

class HistoricalRateLimiter {
  private[this] val log: Logger = LoggerFactory.getLogger(this.getClass)
  
  // Uses req. time (in ms) as key and the actual request as value
  // Ordered from most recent request to oldest ([Ordering[Long].reverse)
  private[this] val requests = TreeMultimap.create[Long, HistoricalRequest](
    implicitly[Ordering[Long]].reverse,
    implicitly[Ordering[HistoricalRequest]]
  )
  // TODO: can we use a scala collection instead of TreeMultiMap here?
  // Note that TreeMultiMap uses comparators to order elems 
  // and offers the .put() method exploited by `def requested()` below

  def now_ms: Long = Calendar.getInstance().getTimeInMillis()

  /* Put a single request into the `requests` TreeMultiMap.
   * @reftime_ms can be used by the method below to assign a delay to this requests
   * in case we're violating a TWS rule!
   */
  def requested(
    request: HistoricalRequest,
    reftime_ms: Option[Long] = None
  ): Unit = {
    var curTime = reftime_ms.getOrElse(now_ms)
    // apparently TreeMultiMap.put returns false if the key you're trying to add already exists...
    while (requests.put(curTime, request) == false) {
      log.warn(s"Incrementing reftime of request ${request}, as there's already a request with the same time ${curTime}")
      curTime += 1
    }
  }
  
  /* Take single HistoricalRequest from requests within a given timeframe */
  protected def latestInLast(
    timeframe_ms: Long,
    reftime_ms: Long = now_ms
  ): Iterator[java.util.Map.Entry[Long, HistoricalRequest]] = {
    requests.entries.iterator.asScala.takeWhile { 
      x => x.getKey > reftime_ms - timeframe_ms
    }
  }

  /* We know that the TWS API has some restrictions: you can send max N requests (@numRequests)
   * of some type (@filter) in a given timeframe (@restrictionWait_ms).
   * Of course there are also restrictions that apply to all requests, that's why @filter is optional.
   * So we retrieve all the latest requests in the relevant timeframe from @reftime_ms (normally = now),
   * we apply the filter and then check they don't exceed @numRequests.
   * If they do, we increase the waiting time. See L78
   * This func is used by nextRequestAfter_ms below.
   */
  def nextSlot_ms(
    restrictionWait_ms: Long,
    numRequests: Int,
    filter: Option[(HistoricalRequest) => Boolean] = None,
    reftime_ms: Long = now_ms
  ): Long = {
    val _latest = latestInLast(restrictionWait_ms, reftime_ms).toVector
    val latest = filter match {
      case Some(f) => _latest.filter(x => f(x.getValue))
      case None => _latest
    }
   
    if (latest.size >= numRequests) {
      // We've taken numRequests from the "queue". Calc the time between the oldest offers
      // those and our reftime_ms.
      val gap = reftime_ms - latest.take(numRequests).last.getKey
      // Our delay will be the diff in ms between that and the waiting time required by the restriction of interest (restrictionWait_ms)
      val nextSlotIn_ms = restrictionWait_ms - gap
      if (nextSlotIn_ms > 0) return nextSlotIn_ms
    }
    0L
  }

  /* Given an HistoricalRequest and our `requests` "queue"
   * Calculate the waiting time before we can place this request in order to respect the restrictions
   * TODO: discuss the `synchronized` call
   */
  def nextRequestAfter_ms(
    request: HistoricalRequest,
    reftime_ms: Long = now_ms
  ): Long = synchronized {
    var after_ms: Long = 0L
    
    // Restriction number 1: no identical req in 15 seconds
    def identicalReq(req: HistoricalRequest): Boolean = { req == request }
    after_ms = math.max(after_ms, nextSlot_ms(15L * 1000, 1, Some(identicalReq), reftime_ms))
    log.debug(s"delayed by ${after_ms}ms (due to limit on identical requests within 15 s)")

    // Restriction number 2: no more than 6 req for the same contract within 2 s
    def sameContract(req: HistoricalRequest) =  {
      (req.contract, req.exchange, req.barSize) == (request.contract, request.exchange, request.barSize)
    }
    after_ms = math.max(after_ms, nextSlot_ms(2L * 1000, 5, Some(sameContract), reftime_ms))
    log.debug(s"delayed by ${after_ms}ms (no more than 6 request for the same contract within 2s)")

    // Restriction 3: no more than 60 requests in 10 minutes
    after_ms = math.max(after_ms, nextSlot_ms(10L * 60 * 1000, 60, None, reftime_ms))
    log.debug(s"delayed by ${after_ms}ms no more than 60 requests in 10 min")
    
    if (after_ms > 0) log.info(s"HistoricalRateLimiter: delaying request for ${after_ms/1000}s") 
    after_ms
  }

  def registerAndGetWait_ms(request: HistoricalRequest): Long = synchronized {
    val wait_ms = nextRequestAfter_ms(request)
    requested(request, Some(now_ms + wait_ms))
    wait_ms
  }

  def cleanupAfter(time_ms: Long): Unit = synchronized {
    val expired = requests.keys.asScala.filter(_ < time_ms)
    expired.foreach(key => requests.removeAll(key))
  }

  def cleanup(reftime_ms: Long = now_ms): Unit = {
    cleanupAfter(reftime_ms + 10L * 60 * 1000)
  }
}
