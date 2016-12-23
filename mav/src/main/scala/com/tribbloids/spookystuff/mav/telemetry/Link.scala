package com.tribbloids.spookystuff.mav.telemetry

import com.tribbloids.spookystuff.mav.actions.{LocationBundle, LocationGlobal, LocationGlobalRelative, LocationLocal, Location}
import com.tribbloids.spookystuff.mav.dsl.{LinkFactories, LinkFactory}
import com.tribbloids.spookystuff.mav.{MAVConf, ReinforcementDepletedException}
import com.tribbloids.spookystuff.session.python._
import com.tribbloids.spookystuff.session.{LocalCleanable, Session}
import com.tribbloids.spookystuff.utils.{SpookyUtils, TreeException}
import com.tribbloids.spookystuff.{PyInterpreterException, SpookyContext, caching}
import org.slf4j.LoggerFactory

import scala.util.Try

case class Endpoint(
                     // remember, one drone can have several telemetry
                     // endpoints: 1 primary and several backups (e.g. text message-based)
                     // TODO: implement telemetry backup mechanism, can use MAVproxy's multiple master feature
                     connStrs: Seq[String],
                     vehicleTypeOpt: Option[String] = None,
                     name: String = "DRONE"
                   ) extends CaseInstanceRef {

  def connStr = connStrs.head
}

object Link extends StaticRef {

  // max 1 per task/thread.
  val driverLocal: caching.ConcurrentMap[PythonDriver, Link] = caching.ConcurrentMap()

  // connStr -> (link, isBusy)
  // only 1 allowed per connStr, how to enforce?
  val existing: caching.ConcurrentMap[Endpoint, LinkWithContext] = caching.ConcurrentMap()

  // won't be used to create any link before its status being recovered by ping daemon.
  val blacklist: caching.ConcurrentSet[Endpoint] = caching.ConcurrentSet()

  //in the air but unused
  //  def idle: Map[String, (ProxyFactory, Link)] = existing.filter {
  //    tuple =>
  //      !blacklist.contains(tuple._1) &&
  //        !tuple._2._2.isBusy
  //  }

  def getOrInitialize(
                       candidates: Seq[Endpoint],
                       factory: LinkFactory,
                       session: Session,
                       locationOpt: Option[Location] = None
                     ): Link = {
    session.initializeDriverIfMissing {
      getOrCreate(candidates, factory, session, locationOpt)
    }
  }

  /**
    * create a telemetry link based on the following order:
    * if one is already created in the same task, reuse it
    * if one is created in a previous task and not busy, use it. The busy status is controlled by whether it has an active python driver.
    *   - if its generated by an obsolete ProxyFactory, terminate the link and immediately recreate a new one with the new ProxyFactory,
    *     being created means the drone is already in the air, and can be deployed much faster
    * * if multiple are created by previous tasks and not busy, use the one that is closest to the first waypoint * (not implemented yet)
    * If none of the above exists, create one from candidates from scratch
    * remember: once the link is created its proxy is bind to it until death.
    */
  def getOrCreate(
                   candidates: Seq[Endpoint],
                   factory: LinkFactory,
                   session: Session,
                   locationOpt: Option[Location] = None
                 ): Link = {

    val local = driverLocal
      .get(session.pythonDriver)

    local.foreach {
      link =>
        LoggerFactory.getLogger(this.getClass).info(
          s"Using existing Link ${link.endpoint.connStr} with the same driver"
        )
    }

    val result = local
      .getOrElse {
        val newLink = recommissionIdle(candidates, factory, session, locationOpt).getOrElse {
          selectAndCreate(candidates, factory, session)
        }
        try {
          newLink.link.Py(session)
        }
        catch {
          case e: Throwable =>
            newLink.clean()
            throw e
        }

        newLink.link
      }
    result
  }

  // CAUTION: this will refit the telemetry link with new Proxy and clean the old one if ProxyFactory is different.
  def recommissionIdle(
                        candidates: Seq[Endpoint],
                        factory: LinkFactory,
                        session: Session,
                        locationOpt: Option[Location] = None
                      ): Option[LinkWithContext] = {

    val result = this.synchronized {
      val existingCandidates: Seq[LinkWithContext] = candidates.collect {
        Function.unlift {
          endpoint =>
            existing.get(endpoint)
        }
      }

      val idleLinks = existingCandidates.filter {
        link =>
          link.link.isIdle
      }

      //TODO: find the closest one!
      val idleLinkOpt = idleLinks.headOption

      idleLinkOpt match {
        case Some(idleLink) =>
          val recommissioned = {
            if (LinkFactories.canCreate(factory, idleLink)) {
              LoggerFactory.getLogger(this.getClass).info {
                s"Recommissioning telemetry Link for ${idleLink.link.endpoint.connStr} with old proxy"
              }
              idleLink.link.putOnHold()
              idleLink
            }
            else {
              idleLink.clean()
              // recreate proxy
              val link = factory.apply(idleLink.link.endpoint).putOnHold()
              LoggerFactory.getLogger(this.getClass).info {
                s"Recommissioning telemetry Link for ${link.endpoint.connStr} with new proxy"
              }
              LinkWithContext(
                link,
                session.spooky,
                factory
              )
            }
          }

          Some(recommissioned)
        case None =>
          LoggerFactory.getLogger(this.getClass).info{
            if (existingCandidates.isEmpty) {
              s"No existing telemetry Link for ${candidates.map(_.connStr).mkString("[", ", ", "]")}"
            }
            else {
              existingCandidates.map {
                link =>
                  assert(!link.link.isIdle)
                  s"${link.link.endpoint.connStr} is busy"
              }
                .mkString("\n")
            }
          }
          None
      }
    }

    result
  }

  def selectAndCreate(
                       candidates: Seq[Endpoint],
                       factory: LinkFactory,
                       session: Session
                     ): LinkWithContext = {

    val newLink = this.synchronized {
      val endpointOpt = candidates.find {
        v =>
          !existing.contains(v) &&
            !blacklist.contains(v)
      }
      val endpoint = endpointOpt
        .getOrElse(
          throw new ReinforcementDepletedException(
            candidates.map {
              candidate =>
                if (blacklist.contains(candidate)) s"${candidate.connStr} is unreachable"
                else s"${candidate.connStr} is busy"
            }
              .mkString(", ")
          )
        )

      create(endpoint, factory, session.spooky)
    }
    newLink
  }

  def create(
              endpoint: Endpoint,
              factory: LinkFactory,
              spooky: SpookyContext
            ): LinkWithContext = {

    val link = factory.apply(endpoint).putOnHold()
    LinkWithContext(
      link,
      spooky,
      factory
    )
  }

  class Binding(
                 override val ref: Link,
                 override val driver: PythonDriver,
                 override val spookyOpt: Option[SpookyContext]
               ) extends PyBinding(ref, driver, spookyOpt) {

    $Helper.autoStart()
    Link.driverLocal += driver -> ref

    object $Helper {
      // will retry 6 times, try twice for Vehicle.connect() in python, if failed, will restart proxy and try again (3 times).
      // after all attempts failed will stop proxy and add endpoint into blacklist.
      def autoStart(): String = try {

        val retries = spookyOpt.map(
          spooky =>
            spooky.conf.submodules.get[MAVConf]().connectionRetries
        ).getOrElse(1)
        SpookyUtils.retry(retries) {
          try {
            ref.proxyOpt.foreach {
              _.managerPy.start()
            }
            val result = Binding.this.start().$repr.get
            result
          }
          catch {
            case e: Throwable =>
              ref.proxyOpt.foreach {
                _.managerPy.stop()
              }
              throw e
          }
        }
      }
      catch {
        case e: PyInterpreterException => //this indicates a possible port conflict
          //TODO: enable after ping daemon

          try {
            ref.detectPossibleConflicts(Option(e.cause).toSeq)
            throw e
          }
          catch {
            case ee: TreeException =>
              throw e.copy(
                cause = ee
              )
          }
      }

      def getLocations: LocationBundle = {

        val locations = Binding.this.vehicle.location
        val global = locations.global_frame.$message.get.cast[LocationGlobal]
        val globalRelative = locations.global_relative_frame.$message.get.cast[LocationGlobalRelative]
        val local = locations.local_frame.$message.get.cast[LocationLocal]

        val result = LocationBundle(
          global,
          globalRelative,
          local
        )
        ref.lastKnownLocations = result
        result
      }
    }

    override def cleanImpl(): Unit = {

      super.cleanImpl()
      val localOpt = Link.driverLocal.get(driver)
      localOpt.foreach {
        v =>
          if (v eq this.ref)
            Link.driverLocal -= driver
      }
    }
  }
}

/**
to keep a drone in the air, a python daemon process D has to be constantly running to
supervise task-irrelevant path planning (e.g. RTL/Position Hold/Avoidance).
This process outlives each task. Who launches D? how to ensure smooth transitioning
of control during Partition1 => D => Partition2 ? Can they share the same
Connection / Endpoint / Proxy ? Do you have to make them picklable ?

GCS:UDP:xxx ------------------------> Proxy:TCP:xxx -> Drone
                                   /
TaskProcess -> Connection:UDP:xx -/
            /
DaemonProcess   (can this be delayed to be implemented later? completely surrender control to GCS after Altitude Hold)
  is Vehicle picklable? if yes then that changes a lot of things.
  but if not ...
    how to ensure that an interpreter can takeover and get the same vehicle?
  */
case class Link(
                 endpoint: Endpoint,
                 outs: Seq[String]
               ) extends CaseInstanceRef with LocalCleanable {

  /**
    * set true to block being used by another thread before its driver is created
    */
  var onHold: Boolean = true
  def putOnHold(): this.type = {
    this.onHold = true
    this
  }
  def isIdle: Boolean = {
    !onHold && validDriverToBindings.isEmpty
  }

  //mnemonic
  @volatile var _proxyOpt: Option[Proxy] = _
  def proxyOpt: Option[Proxy] = Option(_proxyOpt).getOrElse {
    this.synchronized {
      _proxyOpt = if (outs.isEmpty) None
      else {
        val proxy = Proxy(
          endpoint.connStr,
          outs,
          endpoint.name
        )
        Some(proxy)
      }
      _proxyOpt
    }
  }

  var lastKnownLocations: LocationBundle = _

  def wContext(
                spooky: SpookyContext,
                factory: LinkFactory
              ) = LinkWithContext(
    this,
    spooky,
    factory
  )

  /**
    * no duplication due to port conflicts!
    */
  lazy val uri: String = outs.headOption.getOrElse(endpoint.connStr)

  override def _Py(driver: PythonDriver, spookyOpt: Option[SpookyContext]): Link.Binding = {
    validDriverToBindings.get(driver)
      .map(_.asInstanceOf[Link.Binding])
      .getOrElse {
        assert(Link.validDriverToBindings.isEmpty, "Link can only be bind to one driver")
        val result = new Link.Binding(this, driver, spookyOpt)
        onHold = false
        result
      }
  }
  override def Py(session: Session): Link.Binding = {
    _Py(session.pythonDriver, Some(session.spooky))
  }

  def detectPossibleConflicts(causes: Seq[Throwable] = Nil): Unit = {
    val c1 = Link.existing.get(endpoint).forall(_.link eq this)
    val existing = Link.existing.values // remember to clean up the old one to create a new one
    val c2 = existing.filter(_.link.endpoint.connStr == endpoint.connStr).forall(_.link eq this)
    val c3 = existing.filter(_.link.uri == uri).forall(_.link eq this)

    val connStrs: Map[String, Int] = Link.existing.values.flatMap(_.link.endpoint.connStrs)
      .groupBy(identity)
      .mapValues(_.size)

    TreeException.&&&(
      Seq(
        Try(assert(c1, s"Conflict: endpoint (index) ${endpoint.connStr} is already used")),
        Try(assert(c2, s"Conflict: endpoint ${endpoint.connStr} is already used")),
        Try(assert(c3, s"Conflict: uri $uri is already used"))
      ) ++
        connStrs.map {
          tuple =>
            Try(assert(tuple._2 == 1, s"connection String ${tuple._1} is shared by ${tuple._2} endpoints"))
        }
          .toSeq,
      extra = causes
    )
  }

  var isDryrun = false
  //finalizer may kick in and invoke it even if its in Link.existing
  override protected def cleanImpl(): Unit = {

    super.cleanImpl()
    Option(_proxyOpt).flatten.foreach(_.clean())
    val existingOpt = Link.existing.get(this.endpoint)
    existingOpt.foreach {
      v =>
        if (v.link eq this)
          Link.existing -= this.endpoint
        else {
          if (!isDryrun) throw new AssertionError("THIS IS NOT A DRYRUN OBJECT! SO ITS CREATED ILLEGALLY!")
        }
    }
    //otherwise its a zombie Link created by LinkFactories.canCreate
  }
}

case class LinkWithContext(
                            link: Link,
                            spooky: SpookyContext,
                            factory: LinkFactory
                          ) extends LocalCleanable {
  try {
    link.detectPossibleConflicts()

    Link.existing += link.endpoint -> this
    spooky.metrics.linkCreated += 1
  }
  catch {
    case e: Throwable =>
      this.clean()
      throw e
  }

  protected override def cleanImpl(): Unit = {

    link.clean()
    spooky.metrics.linkDestroyed += 1
  }
}