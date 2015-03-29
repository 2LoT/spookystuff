package org.tribbloid.spookystuff

import org.apache.spark.SparkContext

import scala.language.implicitConversions

/**
 * Created by peng on 11/7/14.
 */
//implicit conversions in this package are used for development only
package object views {

  implicit def mapToItsView[K, V](map: Map[K,V]): MapView[K, V] = new MapView(map)

  //  def identical[T](vs: TraversableOnce[T]): Boolean = vs.reduce{
  //    (v1,v2) => {
  //      assert(v1 == v2)
  //      v1
  //    }
  //  }
}