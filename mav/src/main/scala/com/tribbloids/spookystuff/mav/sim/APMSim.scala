package com.tribbloids.spookystuff.mav.sim

import com.tribbloids.spookystuff.caching
import com.tribbloids.spookystuff.session.python.CaseInstanceRef

/**
  * Created by peng on 27/10/16.
  */
object APMSim {

  val existing: caching.ConcurrentSet[APMSim] = caching.ConcurrentSet()

  def next: APMSim = this.synchronized {
    val nextINumOpt = (0 to 254).find{
      i =>
        !existing.map(_.iNum).toSeq.contains(i)
    }
    val nextINum = nextINumOpt
      .getOrElse(
        throw new UnsupportedOperationException("APMSim iNum depleted")
      )
    APMSim(nextINum)
  }
}

case class APMSim private (
                            iNum: Int
                          ) extends CaseInstanceRef {

  APMSim.existing += this

  override def _cleanImpl() = {
    super._cleanImpl()
    APMSim.existing -= this
  }
}