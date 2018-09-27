package ch.epfl.lts2.wikipedia

import org.scalatest._
import java.time._
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql.{SQLContext, Row, DataFrame, SparkSession, Dataset}

case class MergedPagecount (time:Long, title:String, namespace:Int, visits:Int, id:Int)

class PageCountSpec extends FlatSpec with SparkSessionTestWrapper with TestData {
  
  
   "WikipediaHourlyVisitParser" should "parse hourly visits fields correctly" in {
    val p = new WikipediaHourlyVisitsParser
    val d = LocalDate.of(2018, 8, 1)
    val r = p.parseField("J1", d)
    assert(r.size == 1)
    val j1 = r.head
    assert(j1.time.getHour == 9 && j1.visits == 1)
    
    val r2 = p.parseField("C1P3", d)
    assert(r2.size == 2)
    val r2m = r2.map(w => (w.time.getHour, w.visits)).toMap
    assert(r2m(2) == 1 && r2m(15) == 3)
    
    val r3 = p.parseField("A9B13C9D15E7F14G9H8I8J9K8L4M9N18O9P15Q17R12S10T12U7V15W15X6", d)
    assert(r3.size == 24)
    val r3m = r3.map(w => (w.time.getHour, w.visits)).toMap
    assert(r3m(23) == 6 && r3m(22) == 15 && r3m(0) == 9 && r3m(1) == 13)
    
  }
  
  
  "WikipediaPagecountParser" should "parse pagecounts correctly" in {
    val p = new WikipediaPagecountParser
    
    val pcLines = pageCount.split('\n').filter(p => !p.startsWith("#"))
    
    val rdd = p.getRDD(spark.sparkContext.parallelize(pcLines, 2))
    val resMap = rdd.map(w => (w.title, w)).collect().toMap
   
    assert(resMap.keys.size == 20)
    
    val res16 = resMap("16th_amendment")
    assert(res16.namespace == WikipediaNamespace.Page && res16.dailyVisits == 2 && res16.hourlyVisits == "C1P1")
    val res16c = resMap("16th_ammendment")
    assert(res16c.namespace == WikipediaNamespace.Category && res16c.dailyVisits == 1 && res16c.hourlyVisits == "J1")
    assert(resMap.get("16th_century_in_literature") == None) // book -> filtered out
    
    val res16th = resMap("16th_century")
    assert(res16th.dailyVisits == 258 && res16th.namespace == WikipediaNamespace.Page && 
              res16th.hourlyVisits == "A9B13C9D15E7F14G9H8I8J9K8L4M9N18O9P15Q17R12S10T12U7V15W15X6")
  }
  
  "PagecountProcessor" should "generate correct date ranges" in {
    val p = new PagecountProcessor
    val range = p.dateRange(LocalDate.parse("2018-08-01"), LocalDate.parse("2018-08-10"), Period.ofDays(1))
    assert(range.size == 10)
    val r2 = p.dateRange(LocalDate.parse("2017-08-01"), LocalDate.parse("2017-09-01"), Period.ofDays(1))
    assert(r2.size == 32)
  }
  
  it should "read correctly pagecounts" in {
    val p = new PagecountProcessor
    val rdd = p.parseLines(spark.sparkContext.parallelize(pageCount2, 2), 100, LocalDate.of(2018, 8, 1))
    val res1 = rdd.filter(f => f.title == "Anarchism").collect()
    val res2 = rdd.filter(f => f.title == "AfghanistanHistory").collect()
    //spark.createDataFrame(rdd).show()
    assert(res1.size == 5)
    res1.map(p => assert(p.namespace == WikipediaNamespace.Category && p.visits == 60))
    assert(res2.size == 10)
    res2.map(p => assert(p.namespace == WikipediaNamespace.Page && p.visits == 20))
  }
  
  it should "merge page dataframe correctly" in {
    import spark.implicits._
    val p = new PagecountProcessor
    val pcDf = p.parseLinesToDf(spark.sparkContext.parallelize(pageCount2, 2), 100, LocalDate.of(2018, 8, 1))
    val dp = new DumpParser
    
    val df = dp.processToDf(spark, spark.sparkContext.parallelize(Seq(sqlPage), 2), WikipediaDumpType.Page)
    val res = p.mergePagecount(df, pcDf).as[MergedPagecount]
    val res1 = res.filter(p => p.title == "Anarchism").collect()
    val res2 = res.filter(p => p.title == "AfghanistanHistory").collect()
    assert(res1.size == 5)
    res1.map(p => assert(p.id == 12))
    assert(res2.size == 10)
    res2.map(p => assert(p.id == 13))
    
  }
}