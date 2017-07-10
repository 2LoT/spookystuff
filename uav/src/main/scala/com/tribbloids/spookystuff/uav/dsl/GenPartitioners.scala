package com.tribbloids.spookystuff.uav.dsl

import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution
import com.tribbloids.spookystuff.actions.TraceView
import com.tribbloids.spookystuff.dsl.GenPartitioner
import com.tribbloids.spookystuff.dsl.GenPartitioners.Instance
import com.tribbloids.spookystuff.execution.ExecutionContext
import com.tribbloids.spookystuff.row.BeaconRDD
import com.tribbloids.spookystuff.uav.actions.UAVAction
import com.tribbloids.spookystuff.uav.planning.{JSpritSolver, PreferUAV, WrapLocation}
import com.tribbloids.spookystuff.uav.telemetry.{Link, UAVStatus}
import org.apache.spark.rdd.RDD

import scala.reflect.ClassTag

/**
  * Created by peng on 31/12/16.
  */
object GenPartitioners {

  case class JSprit(
                     vizPathOpt: Option[String] = Some("output/GP.png") // for debugging only.
                   ) extends GenPartitioner {

    def getInstance[K >: TraceView: ClassTag](ec: ExecutionContext): Instance[K] = {
      Inst[K](ec)
    }

    case class Inst[K >: TraceView](ec: ExecutionContext)(
      implicit val ctg: ClassTag[K]
    ) extends Instance[K] {

      //gather all UAVActions to driver and use a local solver (JSprit) to rearrange them.
      override def groupByKey[V: ClassTag](
                                            rdd: RDD[(K, V)],
                                            beaconRDDOpt: Option[BeaconRDD[K]]
                                          ): RDD[(K, Iterable[V])] = {

        val spooky = ec.spooky

        val bifurcated: RDD[((Option[TraceView], Option[K]), Seq[V])] = rdd.map {
          case (k: TraceView, v) =>
            val c = k.children
            val result: (Option[TraceView], Option[K]) = {
              if (c.exists(_.isInstanceOf[UAVAction])) Some(k) -> None
              else None -> Some(k: K)
            }
            result -> v
          case (k, v) =>
            val result: (Option[TraceView], Option[K]) = None -> Some(k)
            result -> v
        }
          .groupByKey()
          .mapValues(v => v.toList)

        ec.scratchRDDs.persist(bifurcated)

        //        val hasUAVTraces_Data: Array[(TraceView, V)] = hasUAVRDD.distinct().collect()
        //        val hasUAVTraces = hasUAVTraces_Data.map(_._1.children)

        val hasUAV: Array[(TraceView, Seq[V])] = bifurcated
          .flatMap(tt => tt._1._1.map(v => v -> tt._2)).collect()

        val hasUAVTraces = hasUAV.map(_._1)

        val linkRDD = Link.linkRDD(spooky)
        linkRDD.persist()
        linkRDD.count() //TODO: optional
        val statuses = linkRDD.keys.collect()

        val traces_linkOpts_indices: Array[((TraceView, Option[UAVStatus]), Int)] = {
          val fromLinks: Array[(TraceView, Option[UAVStatus])] =
            statuses.map {
              link =>
                TraceView(List(WrapLocation(link.currentLocation))) -> Some(link)
            }

          val fromTraces: Array[(TraceView, Option[UAVStatus])] =
            hasUAVTraces.map {
              trace =>
                trace -> None
            }

          (fromLinks ++ fromTraces).zipWithIndex
        }

        val best: VehicleRoutingProblemSolution = JSpritSolver.solveTraces(spooky, traces_linkOpts_indices)

        import scala.collection.JavaConverters._

        val routes = best.getRoutes.asScala.toList
        val status_KVs: Seq[(UAVStatus, Seq[(TraceView, Seq[V])])] = routes.map {
          route =>
            val status = statuses.find(_.uav.primaryURI == route.getVehicle.getId).get
            val tours = route.getTourActivities.getActivities.asScala.toList
            val traces = for (tour <- tours) yield {
              val index = tour.getLocation.getIndex
              val trace: TraceView = traces_linkOpts_indices.find(_._2 == index).get._1._1
              val v = hasUAV.find(_._1 == trace).get._2
              trace -> v
            }
            status -> traces
        }
        val status_KVMap = Map(status_KVs: _*)

        val realignedRDD: RDD[(K, Iterable[V])] = linkRDD.flatMap {
          tuple =>
            val link = tuple._2
            val KVs = status_KVMap.getOrElse(tuple._1, Nil)
            val result = KVs.map {
              kv =>
                val vv = kv._1
                val updatedVV = vv.copy(children = List(PreferUAV(tuple._1)) ++ vv.children)
                (updatedVV: K) -> kv._2
            }
            result
        }

        val notHaveUAVRDD: RDD[(K, Iterable[V])] = bifurcated
          .flatMap(tt => tt._1._2.map(v => v -> tt._2))

        val result = realignedRDD.union(notHaveUAVRDD)

        result
      }

      //        val status_traceRDD: RDD[(UAVStatus, Seq[TraceView])] = spooky.sparkContext.parallelize(status_traces)
      //
      //        //TODO: cogroup is expensive
      //        //if you don't know cogroup preserve sequence, don't use it.
      //        val realignedTraceRDD: RDD[(Seq[TraceView], Link)] = linkRDD.cogroup {
      //          status_traceRDD
      //          //linkRDD doesn't have a partitioner so no need to explicit
      //        }
      //          .values
      //          .map {
      //            tuple =>
      //              assert(tuple._1.size == 1)
      //              assert(tuple._2.size == 1)
      //              tuple._2.head -> tuple._1.head
      //          }
      //
      //        val trace_index_linkRDD: RDD[(K, (Int, Link))] = realignedTraceRDD.flatMap {
      //          tuple =>
      //            tuple._1
      //              .zipWithIndex
      //              .map {
      //                tt =>
      //                  tt._1 -> (tt._2 -> tuple._2)
      //              }
      //        }
      //
      //        val cogroupedRDD: RDD[(K, (Iterable[(Int, Link)], Iterable[V]))] =
      //          trace_index_linkRDD.cogroup {
      //            rdd
      //          }
      //
      //        val kv_index = cogroupedRDD.map {
      //          triplet =>
      //            val is_links = triplet._2._1.toSeq
      //            is_links.size match {
      //              case 0 =>
      //                val k = triplet._1
      //                (k -> triplet._2._2) -> -1
      //              case 1 =>
      //                val k = triplet._1.asInstanceOf[TraceView]
      //                val updatedK = k.copy(children = List(PreferUAV(is_links.head._2)) ++ k.children)
      //                (updatedK -> triplet._2._2) -> is_links.head._1
      //              case _ =>
      //                throw new AssertionError(s"size cannot be ${is_links.size}")
      //            }
      //        }
      //
      //        kv_index.mapPartitions {
      //          itr =>
      //            itr.toSeq.sortBy(_._2).map(_._1).iterator
      //        }
      //
      //        // merge with trace that doesn't contain UAVNavigations
      //        // also carry data with them, by treating realigned link_traceRDD like a beaconRDD.
      //      }
    }
  }

  // all adaptive improvements goes here.
  object DRL extends GenPartitioner {

    def getInstance[K: ClassTag](ec: ExecutionContext): Instance[K] = {
      ???
    }
  }
}
