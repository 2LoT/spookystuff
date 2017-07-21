package com.tribbloids.spookystuff.uav.telemetry

import com.tribbloids.spookystuff.caching.Memoize
import com.tribbloids.spookystuff.session._
import com.tribbloids.spookystuff.session.python.PyRef
import com.tribbloids.spookystuff.uav.dsl.LinkFactory
import com.tribbloids.spookystuff.uav.spatial._
import com.tribbloids.spookystuff.uav.system.UAV
import com.tribbloids.spookystuff.uav.telemetry.Link.MutexLock
import com.tribbloids.spookystuff.uav.telemetry.mavlink.MAVLink
import com.tribbloids.spookystuff.uav.{ReinforcementDepletedException, UAVConf, UAVMetrics}
import com.tribbloids.spookystuff.utils.{IDMixin, SpookyUtils, TreeException}
import com.tribbloids.spookystuff.{SpookyContext, caching}
import org.slf4j.LoggerFactory

import scala.collection.mutable.ArrayBuffer
import scala.language.implicitConversions
import scala.util.{Failure, Random, Success, Try}

/**
  * Created by peng on 24/01/17.
  */
object Link {

  // connStr -> (link, isBusy)
  // only 1 allowed per connStr, how to enforce?
  val existing: caching.ConcurrentMap[UAV, Link] = caching.ConcurrentMap()

  def allConflicts: Seq[Try[Unit]] = {
    Seq(
      Try(PyRef.sanityCheck()),
      Try(MAVLink.sanityCheck())
    ) ++
      ConflictDetection.conflicts
  }

  val LOCK_EXPIRE_AFTER = 60 * 1000
  case class MutexLock(
                        _id: Long = Random.nextLong(), //can only be lifted by PreferUAV that has the same token.
                        timestamp: Long = System.currentTimeMillis()
                      ) extends IDMixin {

    def expireAfter = timestamp + LOCK_EXPIRE_AFTER
  }

  case class Selector(
                       fleet: Seq[UAV],
                       session: Session,
                       preference: Seq[Link] => Option[Link] = {
                         vs =>
                           vs.find(_.isAvailable)
                       },
                       recommissionWithNewProxy: Boolean = true
                     ) {

    lazy val spooky = session.spooky
    lazy val ctx = session.lifespan.ctx

    def select: Link = {

      trySelect.get
    }

    def trySelect: Try[Link] = Try {

      SpookyUtils.retry(3, 1000) {
        _trySelect.get
      }
    }

    /**
      * @return a link that is unlocked and owned by session's lifespan
      */
    private def _trySelect: Try[Link] = {

      val threadOpt = Some(ctx.thread)

      // IMPORTANT: ALWAYS set owner first!
      // or the link may become available to other threads and snatched by them
      def setOwnerAndUnlock(v: Link): Link = {
        v.owner = ctx
        v.unlock()
        v
      }

      val threadLocalOpt = Link.synchronized {
        val id2Opt = threadOpt.map(_.getId)
        val local = Link.existing.values.toList.filter {
          v =>
            val id1Opt = v.ownerOpt.map(_.thread.getId)
            val lMatch = (id1Opt, id2Opt) match {
              case (Some(tc1), Some(tc2)) if tc1 == tc2 => true
              case _ => false
            }
            lMatch
        }

        assert(local.size <= 1, "Multiple Links cannot share task context or thread")
        val opt = local.find {
          link =>
            fleet.contains(link.uav)
        }
        opt.map(setOwnerAndUnlock)
      }

      val recommissionedOpt = threadLocalOpt match {
        case None =>
          LoggerFactory.getLogger(this.getClass).info (
            s"ThreadLocal link not found: ${ctx.toString}" +
              s" <\\- ${Link.existing.values.flatMap(_.ownerOpt)
                .mkString("{", ", ", "}")}"
          )
          None
        case Some(threadLocal) =>
          val v = if (recommissionWithNewProxy) {
            val factory = spooky.getConf[UAVConf].linkFactory
            threadLocal.recommission(factory)
          }
          else {
            threadLocal
          }
          Try{
            v.connect()
            v
          }
            .toOption
      }

      // no need to recommission if the link is fre
      val resultOpt = recommissionedOpt
        .orElse {
          val links = fleet.flatMap{
            uav =>
              val vv = uav.getLink(spooky)
              Try {
                vv.connect()
                vv
              }
                .toOption
          }

          val opt = Link.synchronized {
            val opt = preference(links)
            //deliberately set inside synchronized block to avoid being selected by 2 threads
            opt.map(setOwnerAndUnlock)
          }

          opt
        }

      resultOpt match {
        case Some(link) =>
          assert(
            link.owner == ctx,
            s"owner inconsistent! ${link.owner} != ${ctx}"
          )
          Success {
            link
          }
        case None =>
          val info = if (Link.existing.isEmpty) {
            val msg = s"No telemetry Link for ${fleet.mkString("[", ", ", "]")}, existing links are:"
            val hint = Link.existing.keys.toList.mkString("[", ", ", "]")
            msg + "\n" + hint
          }
          else {
            "All telemetry links are busy:\n" +
              Link.existing.values.map {
                link =>
                  link.statusString
              }
                .mkString("\n")
          }
          Failure(
            new ReinforcementDepletedException(info)
          )
      }
    }
  }

  object Selector {

    def withMutex(
                   fleet: Seq[UAV],
                   session: Session,
                   mutexIDOpt: Option[Long]
                 ) = Selector(
      fleet,
      session,
      preference = {
        vs =>
          vs.find(_.isAvailableTo(mutexIDOpt))
      }
    )
  }
}

trait Link extends LocalCleanable with ConflictDetection {

  val uav: UAV

  val exclusiveURIs: Set[String]
  final override lazy val resourceIDs = Map("uris" -> exclusiveURIs)

  @volatile protected var _spooky: SpookyContext = _
  def spookyOpt = Option(_spooky)

  @volatile protected var _factory: LinkFactory = _
  def factoryOpt = Option(_factory)

  lazy val runOnce: Unit = {
    spookyOpt.get.getMetrics[UAVMetrics].linkCreated += 1
  }

  def setFactory(
                  spooky: SpookyContext = this._spooky,
                  factory: LinkFactory = this._factory
                ): this.type = Link.synchronized{

    try {
      _spooky = spooky
      _factory = factory
      //      _taskContext = taskContext
      spookyOpt.foreach(_ => runOnce)

      val inserted = Link.existing.getOrElseUpdate(uav, this)
      assert(
        inserted eq this,
        {
          s"Multiple Links created for UAV $uav"
        }
      )

      this
    }
    catch {
      case e: Throwable =>
        this.clean()
        throw e
    }
  }

  @volatile protected var _owner: LifespanContext = _
  def ownerOpt = Option(_owner)
  def owner = ownerOpt.get
  def owner_=(
               c: LifespanContext
             ): this.type = {

    if (ownerOpt.exists(v => v == c)) this
    else {
      this.synchronized{
        assert(
          isNotUsed,
          s"Unavailable to ${c.toString} until previous thread/task is finished: $statusString"
        )
        this._owner = c
        this
      }
    }
  }

  //finalizer may kick in and invoke it even if its in Link.existing
  override protected def cleanImpl(): Unit = {

    val existingOpt = Link.existing.get(uav)
    existingOpt.foreach {
      v =>
        if (v eq this)
          Link.existing -= uav
    }
    spookyOpt.foreach {
      spooky =>
        spooky.getMetrics[UAVMetrics].linkDestroyed += 1
    }
  }

  var isConnected: Boolean = false
  final def connectIfNot(): Unit = this.synchronized{
    if (!isConnected) {
      _connect()
    }
    isConnected = true
  }
  protected def _connect(): Unit

  final def disconnect(): Unit = this.synchronized{
    _disconnect()
    isConnected = false
  }
  protected def _disconnect(): Unit

  private def connectRetries: Int = spookyOpt
    .map(
      spooky =>
        spooky.getConf[UAVConf].fastConnectionRetries
    )
    .getOrElse(UAVConf.FAST_CONNECTION_RETRIES)

  @volatile var lastFailureOpt: Option[(Throwable, Long)] = None

  protected def detectConflicts(): Unit = {
    val notMe: Seq[Link] = Link.existing.values.toList.filterNot(_ eq this)

    for (
      myURI <- this.exclusiveURIs;
      notMe1 <- notMe
    ) {
      val notMyURIs = notMe1.exclusiveURIs
      assert(!notMyURIs.contains(myURI), s"'$myURI' is already used by link ${notMe1.uav}")
    }
  }

  /**
    * A utility function that all implementation should ideally be enclosed
    * All telemetry are inheritively unstable, so its better to reconnect if anything goes wrong.
    * after all retries are exhausted will try to detect URL conflict and give a report as informative as possible.
    */
  def retry[T](n: Int = connectRetries, interval: Long = 0, silent: Boolean = false)(
    fn: =>T
  ): T = {
    try {
      SpookyUtils.retry(n, interval, silent) {
        try {
          connectIfNot()
          fn
        }
        catch {
          case e: Throwable =>
            disconnect()
            val conflicts = Seq(Failure[Unit](e)) ++
              Seq(Try(detectConflicts())) ++
              Link.allConflicts
            val afterDetection = {
              try {
                TreeException.&&&(conflicts)
                e
              }
              catch {
                case ee: Throwable =>
                  ee
              }
            }
            if (!silent) LoggerFactory.getLogger(this.getClass).warn(s"CONNECTION TO $uav FAILED!", afterDetection)
            throw afterDetection
        }
      }
    }
    catch {
      case e: Throwable =>
        lastFailureOpt = Some(e -> System.currentTimeMillis())
        throw e
    }
  }

  /**
    * set this to avoid being used by another task even the current task finish.
    */
  @volatile var _lock: Option[MutexLock] = None
  def isLocked: Boolean = _lock.exists(v => System.currentTimeMillis() < v.expireAfter)
  def lock(): MutexLock = {
    assert(!isLocked)
    val v = MutexLock()
    _lock = Some(v)
    v
  }
  def unlock(): Unit = {
    _lock = None
  }

  private def blacklistDuration: Long = spookyOpt
    .map(
      spooky =>
        spooky.getConf[UAVConf].slowConnectionRetryInterval
    )
    .getOrElse(UAVConf.BLACKLIST_RESET_AFTER)
    .toMillis

  def isReachable: Boolean = !lastFailureOpt.exists {
    tt =>
      System.currentTimeMillis() - tt._2 <= blacklistDuration
  }

  def isNotUsedByThread: Boolean = {
    ownerOpt.forall(v => !v.thread.isAlive)
  }
  def isNotUsedByTask: Boolean = {
    ownerOpt.flatMap(_.taskOpt).forall(v => v.isCompleted())
  }
  def isNotUsed = isNotUsedByThread || isNotUsedByTask

  def isAvailable: Boolean = {
    !isLocked && isReachable && isNotUsed && !isCleaned
  }
  // return true regardless if given the same MutexID
  def isAvailableTo(mutexIDOpt: Option[Long]): Boolean = {
    isAvailable || {
      mutexIDOpt.exists(
        mutexID =>
          _lock.get._id == mutexID
      )
    }
  }

  // TODO: move to UAVStatus?
  def statusString: String = {

    val strs = ArrayBuffer[String]()
    if (isLocked)
      strs += "locked"
    if (!isReachable)
      strs += s"unreachable for ${(System.currentTimeMillis() - lastFailureOpt.get._2).toDouble / 1000}s" +
        s" (${lastFailureOpt.get._1.getClass.getSimpleName})"
    if (!isNotUsedByThread || !isNotUsedByTask)
      strs += s"used by ${_owner.toString}"

    s"Link $uav is " + {
      if (isAvailable) {
        assert(strs.isEmpty)
        "available"
      }
      else {
        strs.mkString(" & ")
      }
    }
  }

  def coFactory(another: Link): Boolean
  def recommission(
                    factory: LinkFactory
                  ): Link = {

    val neo = Link.synchronized {
      val neo = factory.apply(uav)
      neo.owner = this.owner
      neo
    }
    val result = if (coFactory(neo)) {
      LoggerFactory.getLogger(this.getClass).info {
        s"Reusing existing link for $uav"
      }
      neo.clean(silent = true)
      this
    }
    else {
      LoggerFactory.getLogger(this.getClass).info {
        s"Recreating link for $uav with new factory ${factory.getClass.getSimpleName}"
      }
      this.clean(silent = true)
      neo
    }
    result.setFactory(
      this._spooky,
      factory
    )
    result
  }

  //================== COMMON API ==================

  // will retry 6 times, try twice for Vehicle.connect() in python, if failed, will restart proxy and try again (3 times).
  // after all attempts failed will stop proxy and add endpoint into blacklist.
  // takes a long time.
  def connect(): Unit = {
    retry()(Unit)
  }

  // Most telemetry support setting up multiple landing site.
  protected def _getHome: Location
  protected lazy val getHome: Location = {
    retry(){
      _getHome
    }
  }

  protected def _getCurrentLocation: Location
  protected object CurrentLocation extends Memoize[Unit, Location]{
    override def f(v: Unit): Location = {
      retry() {
        _getCurrentLocation
      }
    }
  }

  def status(expireAfter: Long = 1000): UAVStatus = {
    val current = CurrentLocation.getIfNotExpire((), expireAfter)
    UAVStatus(uav, ownerOpt, getHome, current)
  }

  //====================== Synchronous API ======================
  // TODO this should be abandoned and mimic by Asynch API

  val synch: SynchronousAPI
  abstract class SynchronousAPI {
    def testMove: String

    def clearanceAlt(alt: Double): Unit
    def goto(location: Location): Unit
  }

  //====================== Asynchronous API =====================

  //  val Asynch: AsynchronousAPI
  //  abstract class AsynchronousAPI {
  //    def move(): Unit
  //  }
}
