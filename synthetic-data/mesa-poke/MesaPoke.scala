package com.azavea.geomesa

import com.azavea.common._

import org.apache.spark.{SparkConf, SparkContext}
import org.geotools.data._
import org.geotools.data.simple.SimpleFeatureStore
import org.geotools.factory.Hints
import org.geotools.feature._
import org.locationtech.geomesa.accumulo.data.AccumuloDataStore
import org.locationtech.geomesa.accumulo.index.Constants
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes


object MesaPoke extends CommonPoke {

  def main(args: Array[String]): Unit = {
    val conf = CommandLine.parser.parse(args, CommandLine.DEFAULT_OPTIONS).get

    // Register types with GeoMesa
    val ds = DataStoreFinder.getDataStore(conf.dataSourceConf).asInstanceOf[AccumuloDataStore]
    conf.instructions
      .map({ inst => inst.split(",").head }).distinct
      .map({ kind =>
        kind match {
          case `either` => eitherSft
          case `extent` => extentSft
          case `point` => pointSft
          case str =>
            throw new Exception(str)
        }
      })
      .foreach({ sft => ds.createSchema(sft) })

    // Spark Context
    val sparkConf = (new SparkConf).setAppName("GeoMesa Synthetic Data Ingest")
    val sparkContext = new SparkContext(sparkConf)

    // Create a map of encoded SimpleFeatureTypes.  This map can cross
    // serialization boundary.
    val sftMap = sparkContext.broadcast(
      Map(
        either -> (eitherSft.getTypeName, SimpleFeatureTypes.encodeType(eitherSft)),
        extent -> (extentSft.getTypeName, SimpleFeatureTypes.encodeType(extentSft)),
        point -> (pointSft.getTypeName, SimpleFeatureTypes.encodeType(pointSft))
      )
    )

    // Generate List of Geometries
    val geometries = conf.instructions.flatMap(decode)

    // Store Geometries in GeoMesa
    sparkContext
      .parallelize(geometries, geometries.length)
      .foreach({ tuple =>
        val (name, spec) = sftMap.value.getOrElse(tuple._1, throw new Exception)
        val schema = SimpleFeatureTypes.createType(name, spec)
        val fc =
          tuple match {
            case (_, seed: Long, lng: String, lat: String, time: String, width: String) =>
              GeometryGenerator(schema, seed, lng, lat, time, width)
          }
        val ds = DataStoreFinder.getDataStore(conf.dataSourceConf)

        ds.getFeatureSource(schema.getTypeName).asInstanceOf[SimpleFeatureStore].addFeatures(fc)
        ds.dispose
      })

    sparkContext.stop
  }

  val eitherSft = {
    val sft = CommonSimpleFeatureType("Geometry")
    sft.getUserData.put(Constants.SF_PROPERTY_START_TIME, CommonSimpleFeatureType.whenField) // Inform GeoMesa which field contains "time"
    sft.getUserData.put("geomesa.mixed.geometries", java.lang.Boolean.TRUE) // Allow GeoMesa to index points and extents together
    sft
  }

  val extentSft = {
    val sft = CommonSimpleFeatureType("Polygon")
    sft.getUserData.put(Constants.SF_PROPERTY_START_TIME, CommonSimpleFeatureType.whenField)
    sft
  }

  val pointSft = {
    val sft = CommonSimpleFeatureType("Point")
    sft.getUserData.put(Constants.SF_PROPERTY_START_TIME, CommonSimpleFeatureType.whenField)
    sft
  }
}
