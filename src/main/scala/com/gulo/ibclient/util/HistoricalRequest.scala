package com.gulo.ibclient.util

import java.util.Date

import com.ib.client.Types.{DurationUnit, BarSize}

case class HistoricalRequest(
  contract: String,
  exchange: String,
  endDate: Date,
  duration: Int,
  durationUnit: DurationUnit,
  barSize: BarSize
) extends Ordered[HistoricalRequest] {
  def compare(that: HistoricalRequest): Int = {
    Ordering[(String, String, Date, Int, DurationUnit, BarSize)].compare(
      (this.contract, this.exchange, this.endDate, this.duration, this.durationUnit, this.barSize), (that.contract, that.exchange, that.endDate, that.duration, that.durationUnit, that.barSize))
  }
}
