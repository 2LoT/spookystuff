package com.tribbloids.spookystuff.uav.dsl

import com.tribbloids.spookystuff.actions.TraceView
import com.tribbloids.spookystuff.execution.ExecutionPlan
import com.tribbloids.spookystuff.uav.actions.Waypoint
import com.tribbloids.spookystuff.uav.spatial.NED
import com.tribbloids.spookystuff.uav.{UAVFixture, UAVTestUtils}
import org.scalatest.Ignore

/**
  * Created by peng on 16/06/17.
  */
@Ignore
class GenPartitionersSuite extends UAVFixture {

  override def simURIs = (0 until parallelism).map {
    v =>
      s"dummy:localhost:$v"
  }

  def waypoints(n:Int): Seq[Waypoint] = UAVTestUtils.LawnMowerPattern(
    n,
    NED(10, 10, -10),
    NED(100, 0, 0),
    NED(0, 20, -2)
  )
    .waypoints

  describe("JSpirt") {

    lazy val genPartitioner = GenPartitioners.JSprit()

    def testOnWaypoints(n: Int) = {
      val wps = waypoints(n)
      val rdd = sc.parallelize(
        wps
      ).map {
        wp =>
          val k = TraceView(List(wp))
          k -> Unit
      }

      spooky.rebroadcast()
      val ec = ExecutionPlan.Context(spooky)
      val inst = genPartitioner.Inst(ec)

      val groupedRDD = inst.groupByKey(rdd)

      val grouped = groupedRDD.keys.collect()
      grouped.foreach(println)
    }

    it("can optimize max cost of 1 waypoint per UAV") {

      testOnWaypoints(parallelism)
    }

    it("can optimize max cost of 2.5 waypoints per UAV") {

      testOnWaypoints((parallelism * 2.5).toInt)
    }

    it("can optimize max cost of scans") {


    }
  }
}
