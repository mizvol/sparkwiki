package wiki
import java.nio.file.Paths

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql.{SQLContext, Row, DataFrame, SparkSession}

import scala.RuntimeException
import org.rogach.scallop._

class MergeConf(args: Seq[String]) extends ScallopConf(args) {
  val pagePath = opt[String](name="pagePath", required=true)
  val pageLinksPath = opt[String](name="pageLinksPath")
  val redirectPath = opt[String](name="redirectPath")
  val catlinksPath = opt[String](name="categoryLinksPath")
  val outputPath = opt[String](required=true, name="outputPath")
  
  mutuallyExclusive(pageLinksPath, redirectPath, catlinksPath)
  verify()
}

object DumpParseMerge {
  def writeCsv(df:DataFrame, outputPath:String) = {
    df.write.option("delimiter", "\t")
            .option("header", false)
            .option("quote", "")
            .option("compression", "gzip")
            .csv(outputPath)
  }
  
  def splitPages(session:SparkSession, pages:DataFrame, outputPath:String) = {
    import session.implicits._
    val normal_pages = pages.filter($"namespace" === 0).select("id", "title", "isRedirect", "isNew")
    val cat_pages = pages.filter($"namespace" === 14).select("id", "title", "isRedirect", "isNew")
    writeCsv(normal_pages, Paths.get(outputPath, "normal_pages").toString)
    writeCsv(cat_pages, Paths.get(outputPath, "category_pages").toString)
    
  }
  def joinPageLinks(session:SparkSession, pages:DataFrame, pageLinkPath:String, outputPath:String) = {
    import session.implicits._
   
    val pagelinks = session.read.parquet(pageLinkPath)
    val pagelinks_id = pagelinks.join(pages, Seq("title", "namespace"))
                                .select("from", "id", "title", "fromNamespace", "namespace")
    writeCsv(pagelinks_id, outputPath)                            
   }
  
  def joinRedirect(session:SparkSession, pages:DataFrame, redirectPath:String, outputPath:String) = {
    val redirect = session.read.parquet(redirectPath)
    val redirect_id = redirect.withColumn("namespace", redirect.col("targetNamespace"))
                              .join(pages, Seq("title", "namespace")).select("from", "id", "title")
    
    writeCsv(redirect_id, outputPath)
  }
  
  def joinCategory(session:SparkSession, pages:DataFrame, categoryLinksPath:String, outputPath:String) = {
    import session.implicits._
    val catlinks = session.read.parquet(categoryLinksPath)
    val cat_pages = pages.filter($"namespace" === 14).select("id", "title")
    val catlinks_pg = catlinks.withColumn("id", catlinks.col("from"))
                              .join(pages, "id")
                              .select("from", "to")
    
    // this will only show categories having a matching page (in namespace 14)
    val catlinks_id = catlinks_pg.withColumn("title", catlinks.col("to"))
                          .join(cat_pages, "title")
                          .select("from", "title", "id", "ctype")
    writeCsv(catlinks_id, outputPath)
  }
  
  
  def main(args: Array[String]) {
    val conf = new MergeConf(args)
    
    val sconf = new SparkConf().setAppName("Wikipedia dump merge").setMaster("local[*]")
    val session = SparkSession.builder.config(sconf).getOrCreate()
    val sctx = session.sparkContext
    
    val pages = session.read.parquet(conf.pagePath())
    
    if (!conf.pageLinksPath.isEmpty) joinPageLinks(session, pages, conf.pageLinksPath(), conf.outputPath())
    else if (!conf.catlinksPath.isEmpty) joinCategory(session, pages, conf.catlinksPath(), conf.outputPath())
    else if (!conf.redirectPath.isEmpty) joinRedirect(session, pages, conf.redirectPath(), conf.outputPath())
    else splitPages(session, pages, conf.outputPath())
      
  }
}