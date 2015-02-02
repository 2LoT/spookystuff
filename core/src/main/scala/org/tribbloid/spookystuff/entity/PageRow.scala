package org.tribbloid.spookystuff.entity

import org.slf4j.LoggerFactory
import org.tribbloid.spookystuff.actions._
import org.tribbloid.spookystuff.dsl._
import org.tribbloid.spookystuff.expressions._
import org.tribbloid.spookystuff.pages._
import org.tribbloid.spookystuff.utils._
import org.tribbloid.spookystuff.{Const, SpookyContext}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

/**
 * Created by peng on 8/29/14.
 */
//some guideline: All key parameters are Symbols to align with Spark SQL.
//cells & pages share the same key pool but different data structure
case class PageRow(
                    cells: Map[KeyLike, Any] = Map(), //TODO: also carry PageUID & property type (Vertex/Edge) for GraphX, ListMap may be slower but has tighter serialization footage
                    pageLikes: Seq[PageLike] = Seq() // discarded after new page coming in
                    )
  extends Serializable {

  def pages: Seq[Page] = pageLikes.flatMap {
    case page: Page => Some(page)
    case _ => None
  }

  def noPages: Seq[NoPage] = pageLikes.flatMap {
    case noPage: NoPage => Some(noPage)
    case _ => None
  }

  private def resolveKey(keyStr: String): KeyLike = {
    val tempKey = TempKey(keyStr)
    if (cells.contains(tempKey)) tempKey
    else Key(keyStr)
  }

  //TempKey precedes ordinary Key because they are ephemeral
  def getTyped[T: ClassTag](keyStr: String): Option[T] = {
    this.get(keyStr).flatMap {
      case v: T => Some(v)
      case _ => None
    }
  }

  def get(keyStr: String): Option[Any] = {
    cells.get(resolveKey(keyStr))
  }

  def getOnlyPage: Option[Page] = {
    val pages = this.pages

    if (pages.size > 1) throw new UnsupportedOperationException("Ambiguous key referring to multiple pages")
    else if (pages.size == 0) None
    else Some(pages(0))
  }

  def getAllPages: Seq[Page] = this.pages

  def getPage(keyStr: String): Option[Page] = {

    if (keyStr == Const.onlyPageWildcard) return getOnlyPage

    val pages = this.pages.filter(_.name == keyStr)

    if (pages.size > 1) throw new UnsupportedOperationException("Ambiguous key referring to multiple pages")
    else if (pages.size == 0) None
    else Some(pages(0))
  }

  def getUnstructured(keyStr: String): Option[Unstructured] = {

    val page = getPage(keyStr)
    val value = get(keyStr).flatMap {
      case u: Unstructured => Option(u)
      case _ => None
    }

    if (page.nonEmpty && value.nonEmpty) throw new UnsupportedOperationException("Ambiguous key referring to both page and data")
    else page.orElse(value)
  }

  //replace each '{key} in a string with their respective value in cells
  def replaceInto(
                   str: String,
                   delimiter: String = Const.keyDelimiter
                   ): Option[String] = {
    if (str == null) return None
    if (str.isEmpty) return Some(str)

    val regex = (delimiter+"\\{[^\\{\\}\r\n]*\\}").r

    val result = regex.replaceAllIn(str,m => {
      val original = m.group(0)
      val key = original.substring(2, original.size-1)
      this.get(key) match {
        case Some(v) => v.toString
        case None => return None
      }
    })

    Some(result)
  }

  def signature(ignore: Iterable[String]) = (
    this.cells.filterKeys(_.isInstanceOf[Key]).map(identity) -- ignore.map(Key(_)),
    pagesSignature
    )

  def pagesSignature = pages.map(_.uid) -> pages.map(_.name)

  def asMap(): Map[String, Any] = this.cells
    .filterKeys(_.isInstanceOf[Key]).map(identity)
    .map( tuple => tuple._1.name -> tuple._2)

  def toJSON(): String = {
    import org.tribbloid.spookystuff.views._

    Utils.toJson(this.asMap().canonizeKeysToColumnNames)
  }

  def select(exprs: Expression[Any]*): Option[PageRow] = {
    val newKVs = exprs.flatMap{
      expr =>
        val value = expr(this)
        value match {
          case Some(v) => Some(Key(expr.name) -> v)
          case None => None
        }
    }
    Some(this.copy(cells = this.cells ++ newKVs))
  }

  def selectTemp(exprs: Expression[Any]*): Option[PageRow] = {
    val newKVs = exprs.flatMap{
      expr =>
        val value = expr(this)
        value match {
          case Some(v) => Some(TempKey(expr.name) -> v)
          case None => None
        }
    }
    Some(this.copy(cells = this.cells ++ newKVs))
  }

  def remove(keys: Seq[KeyLike]): PageRow = {
    this.copy(cells = this.cells -- keys)
  }

  def filterKeys(f: KeyLike => Boolean): PageRow = {
    this.copy(cells = this.cells.filterKeys(f).map(identity))
  }

  def clearTemp: PageRow = this.filterKeys(!_.isInstanceOf[TempKey])

  def putPages(others: Seq[PageLike], joinType: JoinType): Option[PageRow] = {
    joinType match {
      case Inner =>
        if (others.isEmpty) None
        else Some(this.copy(pageLikes = others))
      case LeftOuter =>
        Some(this.copy(pageLikes = others))
      case Replace =>
        if (others.isEmpty) Some(this)
        else Some(this.copy(pageLikes = others))
      case Append =>
        Some(this.copy(pageLikes = this.pageLikes ++ others))
      case Merge =>
        val oldUids = this.pageLikes.map(_.uid)
        val newPages = others.filter(newPage => !oldUids.contains(newPage.uid))
        Some(this.copy(pageLikes = this.pageLikes ++ newPages))
    }
  }

  //retain old pageRow,
  //always left
  def flatten(
               keyStr: String,
               indexKey: Key,
               limit: Int,
               left: Boolean
               ): Seq[PageRow] = {

    val key = resolveKey(keyStr)

    import org.tribbloid.spookystuff.views._

    val newCells =cells.flattenKey(key, indexKey).slice(0, limit)

    if (left && newCells.isEmpty) {
      Seq(this.copy(cells = this.cells - key)) //this will make sure you dont't lose anything
    }
    else {
      newCells.map(newCell => this.copy(cells = newCell))
    }
  }

  //always left, discard old page row
  //warning: sometimes this always lose information regardless of pattern, e.g. all NoPage will be discarded
  //this operation will try to keep NoPages in the first row for lookup
  def flattenPages(
                    pattern: String, //TODO: enable soon
                    indexKey: Key
                    ): Seq[PageRow] = {

    val result = if (indexKey == null) {
      this.pages.map{
        page => this.copy(cells = this.cells, pageLikes = Seq(page))
      }
    }
    else {
      this.pages.zipWithIndex.map{
        tuple => this.copy(cells = this.cells + (indexKey -> tuple._2), pageLikes = Seq(tuple._1))
      }
    }

    if (result.isEmpty) {
      Seq(this.copy(pageLikes = this.noPages))
    }
    else {
      result.zipWithIndex.map{
        tuple =>
          if (tuple._2 == 0) tuple._1.copy(pageLikes = tuple._1.pageLikes ++ this.noPages)
          else tuple._1
      }
    }
  }

  //affect last page
  //TODO: deprecate?
  def paginate(
                selector: String,
                attr: String,
                wget: Boolean,
                postActions: Seq[Action]
                )(
                limit: Int,
                indexKey: Key,
                flatten: Boolean
                )(
                spooky: SpookyContext
                ): Seq[PageRow] = {

    var currentRow = this
    var increment = this.pageLikes.size

    while (currentRow.pages.size <= limit && increment > 0 && currentRow.pages.last.children(selector).attrs(attr, noEmpty = true).nonEmpty) {

      val action = if (!wget) Visit(new Literal(currentRow.pages.last.children(selector).attrs(attr, noEmpty = true).head))
      else Wget(new Literal(currentRow.pages.last.children(selector).attrs(attr, noEmpty = true).head))

      val newRow = currentRow.putPages(Trace(action::Nil).resolve(spooky), joinType = Merge).get

      increment = newRow.pageLikes.size - currentRow.pageLikes.size

      currentRow = newRow
    }

    if (flatten) currentRow.flattenPages(Const.onlyPageWildcard,indexKey = indexKey)
    else Seq(currentRow)
  }
}

object PageRow {

  //won't insert depthKey for seed.
  def dumbExplore(
                   seeds: Seq[PageRow], //should have the same depth
                   excludeTraces: mutable.Set[Trace],
                   excludeDryruns: Set[Seq[Trace]] = Set()
                   )(
                   expr: Expression[Any],
                   depthKey: Symbol,
                   maxDepth: Int,
                   spooky: SpookyContext
                   )(
                   _traces: Set[Trace],
                   flattenPagesPattern: Symbol,
                   flattenPagesIndexKey: Symbol
                   ): (Iterable[PageRow], Iterable[PageRow]) = {

    val total: ArrayBuffer[PageRow] = ArrayBuffer(seeds: _*)

    var previousRows: Iterable[PageRow] = seeds

    for (depth <- 1 to maxDepth) {
      val squashes = previousRows
        .flatMap(_.selectTemp(expr))
        .flatMap(_.flatten(expr.name, null, Int.MaxValue, left = true))
        .flatMap {
        row =>
          _traces.interpolate(row)
            .filterNot {
            trace =>
              val traceExists = excludeTraces.contains(trace)
              val dryrunExists = excludeDryruns.contains(trace.dryrun)
              traceExists || dryrunExists
          }
            .map(interpolatedTrace => interpolatedTrace -> row)
      }
        .groupBy(_._1)
        .map {
        tuple =>
          Squash(tuple._1, tuple._2.map(_._2).headOption)
      }

      excludeTraces ++= squashes.map(_.trace)

      val newRows = squashes
        .flatMap {
        squash =>
          squash.resolveAndPut(Inner, spooky)
      }
        .flatMap {
        row =>
          if (flattenPagesPattern != null) row.flattenPages(flattenPagesPattern.name, Key(flattenPagesIndexKey))
          else Seq(row)
      }

      LoggerFactory.getLogger(this.getClass).info(s"found ${newRows.size} new row(s) at $depth depth")
      if (newRows.size == 0) return (total, previousRows)

      val newRowsWithDepthKey = if (depthKey != null) newRows.flatMap(_.select(Literal(depth) ~ depthKey))
      else newRows

      total ++= newRowsWithDepthKey

      previousRows = newRows
    }

    (total, previousRows)
  }


  def discoverLatestBlockOutput(pages: Iterable[PageLike]): Option[Seq[PageLike]] = {
    //assume that all inputs already has identical backtraces

    if (pages.isEmpty) return None

    val blockIndexToPages = mutable.HashMap[Int, PageLike]()
    for (page <- pages) {
      val oldPage = blockIndexToPages.get(page.uid.blockIndex)
      if (oldPage.isEmpty || page.laterThan(oldPage.get)) blockIndexToPages.put(page.uid.blockIndex, page)
    }
    val sorted = blockIndexToPages.toSeq.sortBy(_._1).map(_._2)

    //extensive sanity check to make sure that none of them are obsolete
    val total = sorted.head.uid.total
    if (sorted.size < total) return None
    val trunk = sorted.slice(0, sorted.head.uid.total)

    trunk.foreach{
      page =>
        if (page.uid.total != total) return None
    }

    Some(sorted.slice(0, sorted.head.uid.total))
  }
}

//squash identical trace together even PageRows are different
case class Squash(trace: Trace, rows: Iterable[PageRow]) {
  override def hashCode(): Int ={
    trace.hashCode()
  }

  override def equals(obj: Any): Boolean = {
    obj match {
      case s: Squash => this.trace.equals(s.trace)
      case _ => false
    }
  }

  def resolveAndPut(joinType: JoinType, spooky: SpookyContext) = {
    val pages = this.trace.resolve(spooky)
    this.rows.flatMap(_.putPages(pages, joinType))
  }
}