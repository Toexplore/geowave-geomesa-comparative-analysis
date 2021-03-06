package com.azavea.ingest.common.csv

import org.apache.spark._
import org.apache.spark.rdd.RDD
import org.geotools.data.DataStoreFinder
import org.geotools.data.simple.SimpleFeatureStore
import org.opengis.feature.simple._
import org.geotools.feature.simple._
import org.geotools.feature._
import com.amazonaws.auth._
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ListObjectsRequest

import java.util._
import java.util.zip.GZIPInputStream
import java.io._
import scala.collection.JavaConversions._
import scala.collection.mutable

import com.azavea.ingest.common._

object HydrateRDD {

  def getCsvUrls(s3bucket: String, s3prefix: String, extension: String, recursive: Boolean = true): Array[String] =
    Util.listKeys(s3bucket, s3prefix, extension, recursive)

  def csvUrlsToLinesRdd(
    urlArray: Array[String],
    drop: Int,
    numPartitions: Int
  )(implicit sc: SparkContext): RDD[String] = {
    val urlRdd = sc.parallelize(urlArray, urlArray.size)
    urlRdd.flatMap({ address =>
      val url = new java.net.URL(address)

      val reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(url.openStream)))
      val iter: Iterator[String] = reader.lines.iterator
      for (i <- 1 to drop) {
        iter.next()
      }
      iter
    }).repartition(numPartitions)
  }

  def csvLinesToSfRdd(schema: CSVSchemaParser.Expr,
                    lines: RDD[String],
                    delim: String,
                    sftName: String
  )(implicit sc: SparkContext): RDD[SimpleFeature] =
    lines.mapPartitions({ lineIter =>
      lineIter.map({ line =>
        val row: Array[String] = line.split(delim)

        schema.makeSimpleFeature(sftName, row)
      })
    })

  def csvUrlsToRdd(
    urlArray: Array[String],
    sftName: String,
    schema: CSVSchemaParser.Expr,
    drop: Int,
    delim: String,
    unzip: Boolean = false
  )(implicit sc: SparkContext): RDD[SimpleFeature] = {
    val partitionCount = if (unzip) urlArray.size else (urlArray.size / 10)
    val urlRdd: RDD[String] = sc.parallelize(urlArray, partitionCount)
    urlRdd.mapPartitions({ urlIter =>
      urlIter.flatMap({ urlName =>
        val url = new java.net.URL(urlName)
        val featureCollection = new DefaultFeatureCollection(null, null)

        try {
          CSVtoSimpleFeature.parseCSVFile(schema, url, drop, delim, sftName, featureCollection, unzip)
        } catch {
          case e: java.io.IOException =>
            println(s"Discarded ${url} (File does not exist?)")
          case e: Exception =>
            println(e.getMessage())
        }

        featureCollection
      })
    })
  }

}
