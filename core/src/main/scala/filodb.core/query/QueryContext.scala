package filodb.core.query

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._

import filodb.core.{SpreadChange, SpreadProvider}
import filodb.memory.EvictionLock

trait TsdbQueryParams

/**
  * This class provides PromQl query parameters
  * Config has routing parameters
 *  startSecs, stepSecs, endSecs should not be used for query execution as it can be changed by query planner
  */
case class PromQlQueryParams(promQl: String, startSecs: Long, stepSecs: Long, endSecs: Long , remoteQueryPath:
                            Option[String] = None, verbose: Boolean = false) extends TsdbQueryParams

case object UnavailablePromQlQueryParams extends TsdbQueryParams

case class PlannerParams(applicationId: String = "filodb",
                         spread: Option[Int] = None,
                         spreadOverride: Option[SpreadProvider] = None,
                         shardOverrides: Option[Seq[Int]] = None,
                         queryTimeoutMillis: Int = 30000,
                         sampleLimit: Int = 1000000,
                         groupByCardLimit: Int = 100000,
                         joinQueryCardLimit: Int = 100000,
                         timeSplitEnabled: Boolean = false,
                         minTimeRangeForSplitMs: Long = 1.day.toMillis,
                         splitSizeMs: Long = 1.day.toMillis,
                         skipAggregatePresent: Boolean = false,
                         processFailure: Boolean = true,
                         processMultiPartition: Boolean = false,
                         allowPartialResults: Boolean = false)
object PlannerParams {
  def apply(constSpread: Option[SpreadProvider], sampleLimit: Int): PlannerParams =
    PlannerParams(spreadOverride = constSpread, sampleLimit = sampleLimit)
}
/**
  * This class provides general query processing parameters
  */
final case class QueryContext(origQueryParams: TsdbQueryParams = UnavailablePromQlQueryParams,
                              queryId: String = UUID.randomUUID().toString,
                              submitTime: Long = System.currentTimeMillis(),
                              plannerParams: PlannerParams = PlannerParams(),
                              traceInfo: Map[String, String] = Map.empty[String, String])

object QueryContext {
  def apply(constSpread: Option[SpreadProvider], sampleLimit: Int): QueryContext =
    QueryContext(plannerParams = PlannerParams(constSpread, sampleLimit))

  def apply(queryParams: TsdbQueryParams, constSpread: Option[SpreadProvider]): QueryContext =
    QueryContext(origQueryParams = queryParams, plannerParams = PlannerParams(spreadOverride = constSpread))

  /**
    * Creates a spreadFunc that looks for a particular filter with keyName Equals a value, and then maps values
    * present in the spreadMap to specific spread values, with a default if the filter/value not present in the map
    */
  def simpleMapSpreadFunc(shardKeyNames: Seq[String],
                          spreadMap: collection.mutable.Map[collection.Map[String, String], Int],
                          defaultSpread: Int): Seq[ColumnFilter] => Seq[SpreadChange] = {
    filters: Seq[ColumnFilter] =>
      val shardKeysInQuery = filters.collect {
        case ColumnFilter(key, Filter.Equals(filtVal: String)) if shardKeyNames.contains(key) => key -> filtVal
      }
      Seq(SpreadChange(spread = spreadMap.getOrElse(shardKeysInQuery.toMap, defaultSpread)))
  }

  import collection.JavaConverters._

  def simpleMapSpreadFunc(shardKeyNames: java.util.List[String],
                          spreadMap: java.util.Map[java.util.Map[String, String], Integer],
                          defaultSpread: Int): Seq[ColumnFilter] => Seq[SpreadChange] = {
    val spreadAssignment: collection.mutable.Map[collection.Map[String, String], Int]= spreadMap.asScala.map {
      case (d, v) => d.asScala -> v.toInt
    }

    simpleMapSpreadFunc(shardKeyNames.asScala, spreadAssignment, defaultSpread)
  }
}

/**
  * Placeholder for query related information. Typically passed along query execution path.
  *
  * IMPORTANT: The param catchMultipleLockSetErrors should be false
  * only in unit test code for ease of use.
  */
case class QuerySession(qContext: QueryContext,
                        queryConfig: QueryConfig,
                        catchMultipleLockSetErrors: Boolean = false) {

  val queryStats: QueryStats = QueryStats()
  private var lock: Option[EvictionLock] = None
  var resultCouldBePartial: Boolean = false
  var partialResultsReason: Option[String] = None

  def setLock(toSet: EvictionLock): Unit = {
    if (catchMultipleLockSetErrors && lock.isDefined)
      throw new IllegalStateException(s"Assigning eviction lock to session two times $qContext")
    lock = Some(toSet)
  }

  def close(): Unit = {
    lock.foreach(_.releaseSharedLock(qContext.queryId))
    lock = None
  }
}

case class Stat() {
  val partsScanned = new AtomicInteger
  val chunksScanned = new AtomicInteger
  val resultSize = new AtomicLong
  override def toString: String = s"(partsScanned=$partsScanned, chunksScanned=$chunksScanned, resultSize=$resultSize)"
  def add(s: Stat): Unit = {
    partsScanned.addAndGet(s.partsScanned.get())
    chunksScanned.addAndGet(s.chunksScanned.get())
    resultSize.addAndGet(s.resultSize.get())
  }
}

case class QueryStats() {
  val stat = TrieMap[Seq[String], Stat]()

  override def toString: String = stat.toString()

  def add(s: QueryStats): Unit = {
    s.stat.foreach(kv => stat.getOrElseUpdate(kv._1, Stat()).add(kv._2))
  }

  def getPartsScannedCounter(group: Seq[String] = Nil): AtomicInteger = {
    val theNs = if (group.isEmpty && stat.size == 1) stat.head._1 else group
    stat.getOrElseUpdate(theNs, Stat()).partsScanned
  }

  def getChunksScannedCounter(group: Seq[String] = Nil): AtomicInteger = {
    val theNs = if (group.isEmpty && stat.size == 1) stat.head._1 else group
    stat.getOrElseUpdate(theNs, Stat()).chunksScanned
  }

  def getResultSizeCounter(group: Seq[String] = Nil): AtomicLong = {
    val theNs = if (group.isEmpty && stat.size == 1) stat.head._1 else group
    stat.getOrElseUpdate(theNs, Stat()).resultSize
  }

}

object QuerySession {
  def makeForTestingOnly(): QuerySession = QuerySession(QueryContext(), EmptyQueryConfig)
}