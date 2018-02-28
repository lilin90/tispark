package org.apache.spark.sql.statistics

import com.google.common.collect.ImmutableList
import com.pingcap.tikv.expression.ComparisonBinaryExpression._
import com.pingcap.tikv.expression._
import com.pingcap.tikv.meta.{TiIndexInfo, TiTableInfo}
import com.pingcap.tikv.predicates.PredicateUtils.expressionToIndexRanges
import com.pingcap.tikv.predicates.ScanAnalyzer
import com.pingcap.tispark.statistics.StatisticsManager
import org.apache.spark.sql.BaseTiSparkSuite
import org.apache.spark.sql.execution.HandleRDDExec

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

class StatisticsManagerSuite extends BaseTiSparkSuite {
  protected var fDataTbl: TiTableInfo = _
  protected var fDataIdxTbl: TiTableInfo = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    initStatistics()
    loadStatistics()
  }

  private def initStatistics(): Unit = {
    setLogLevel("INFO")
    logger.info("Analyzing table full_data_type_table_idx...")
    tidbStmt.execute("analyze table full_data_type_table_idx")
    logger.info("Analyzing table full_data_type_table...")
    tidbStmt.execute("analyze table full_data_type_table")
    logger.info("Analyzing table finished.")
    setLogLevel("WARN")
  }

  private def loadStatistics(): Unit = {
    fDataIdxTbl = ti.meta.getTable("tispark_test", "full_data_type_table_idx").get
    fDataTbl = ti.meta.getTable("tispark_test", "full_data_type_table").get
    ti.statisticsManager.tableStatsFromStorage(fDataIdxTbl)
    ti.statisticsManager.tableStatsFromStorage(fDataTbl)
  }

  test("select count(1) from full_data_type_table_idx where tp_int = 2006469139 or tp_int < 0") {
    val indexes = fDataIdxTbl.getIndices
    val idx = indexes.filter(_.getIndexColumns.asScala.exists(_.matchName("tp_int"))).head

    val eq1: Expression =
      equal(ColumnRef.create("tp_int", fDataIdxTbl), Constant.create(2006469139))
    val eq2: Expression = lessEqual(ColumnRef.create("tp_int", fDataIdxTbl), Constant.create(0))
    val or: Expression = LogicalBinaryExpression.or(eq1, eq2)

    val expressions = ImmutableList.of(or)
    testSelectRowCount(expressions, idx, 46)
  }

  test("select tp_int from full_data_type_table_idx where tp_int < 5390653 and tp_int > -46759812") {
    val indexes = fDataIdxTbl.getIndices
    val idx = indexes.filter(_.getIndexColumns.asScala.exists(_.matchName("tp_int"))).head

    val le1: Expression =
      lessThan(ColumnRef.create("tp_int", fDataIdxTbl), Constant.create(5390653))
    val gt: Expression =
      greaterThan(ColumnRef.create("tp_int", fDataIdxTbl), Constant.create(-46759812))
    val and: Expression = LogicalBinaryExpression.and(le1, gt)

    val expressions = ImmutableList.of(and)
    testSelectRowCount(expressions, idx, 5)
  }

  def testSelectRowCount(expressions: Seq[Expression],
                         idx: TiIndexInfo,
                         expectedCount: Long): Unit = {
    val result = ScanAnalyzer.extractConditions(expressions, fDataIdxTbl, idx)
    val irs = expressionToIndexRanges(result.getPointPredicates, result.getRangePredicate)
    val tblStatistics = StatisticsManager.getInstance().getTableStatistics(fDataIdxTbl.getId)
    val idxStatistics = tblStatistics.getIndexHistMap.get(idx.getId)
    val rc = idxStatistics.getRowCount(irs).toLong
    assert(rc == expectedCount)
  }

  // TODO: consider covering index cases
  val indexSelectionCases = Map(
    "select id_dt from full_data_type_table_idx where tp_int = 2333" -> "idx_tp_int",
    "select tp_int from full_data_type_table_idx where tp_bigint < 10 and tp_int < 40" -> "idx_tp_int",
    "select tp_int from full_data_type_table_idx where tp_bigint < -4511898209778166952 and tp_int < 40" -> "idx_tp_bigint"
  )

  indexSelectionCases.foreach((t: (String, String)) => {
    val query = t._1
    val idxName = t._2
    test(query) {
      val handleRDDExec = extractHandleRDDExec(query)
      val usedIdxName = extractUsedIndex(handleRDDExec)
      assert(usedIdxName.equals(idxName))
    }
  })

  /**
   * Extract first handle rdd exec node from the given query
   *
   * @throws java.util.NoSuchElementException if the query does not contain any handle rdd exec node.
   */
  private def extractHandleRDDExec(query: String): HandleRDDExec = {
    spark
      .sql(query)
      .queryExecution
      .executedPlan
      .find(_.isInstanceOf[HandleRDDExec])
      .get
      .asInstanceOf[HandleRDDExec]
  }

  private def extractUsedIndex(handleRDDExec: HandleRDDExec): String = {
    handleRDDExec.tiHandleRDD.dagRequest.getIndexInfo.getName
  }
}