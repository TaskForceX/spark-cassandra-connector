package org.apache.spark.sql.cassandra

import org.apache.spark.{Logging, SparkConf}

import org.apache.spark.sql.{DataFrame, SaveMode, SQLContext}
import org.apache.spark.sql.SaveMode._
import org.apache.spark.sql.sources.{CreatableRelationProvider, SchemaRelationProvider, BaseRelation, RelationProvider}
import org.apache.spark.sql.types.StructType

import com.datastax.spark.connector.cql.CassandraConnectorConf
import com.datastax.spark.connector.rdd.ReadConf
import com.datastax.spark.connector.writer.WriteConf

import DefaultSource._

/**
 * Cassandra data source extends [[RelationProvider]], [[SchemaRelationProvider]] and [[CreatableRelationProvider]].
 * It's used internally by Spark SQL to create Relation for a table which specifies the Cassandra data source
 * e.g.
 *
 *      CREATE TEMPORARY TABLE tmpTable
 *      USING org.apache.spark.sql.cassandra
 *      OPTIONS (
 *       c_table "table",
 *       keyspace "keyspace",
 *       cluster "test_cluster",
 *       push_down "true",
 *       spark_cassandra_input_page_row_size "10",
 *       spark_cassandra_output_consistency_level "ONE",
 *       spark_cassandra_connection_timeout_ms "1000"
 *      )
 */
class DefaultSource extends RelationProvider with SchemaRelationProvider with CreatableRelationProvider with Logging {

  /**
   * Creates a new relation for a cassandra table.
   * The parameters map stores table level data. User can specify vale for following keys
   *
   *    c_table        -- table name, required
   *    keyspace       -- keyspace name, required
   *    cluster        -- cluster name, optional, default name is "default"
   *    push_down      -- true/false, optional, default is true
   *    Cassandra connection settings  -- optional, e.g. spark_cassandra_connection_timeout_ms
   *    Cassandra Read Settings        -- optional, e.g. spark_cassandra_input_page_row_size
   *    Cassandra Write settings       -- optional, e.g. spark_cassandra_output_consistency_level
   *
   * When push_down is true, some filters are pushed down to CQL.
   *
   */
  override def createRelation(
    sqlContext: SQLContext,
    parameters: Map[String, String]): BaseRelation = {

    val (tableRef, options) = TableRefAndOptions(parameters)
    CassandraSourceRelation(tableRef, sqlContext, options)
  }

  /**
   * Creates a new relation for a cassandra table given table, keyspace, cluster and push_down
   * as parameters and explicitly pass schema [[StructType]] as a parameter
   */
  override def createRelation(
    sqlContext: SQLContext,
    parameters: Map[String, String],
    schema: StructType): BaseRelation = {

    val (tableRef, options) = TableRefAndOptions(parameters)
    CassandraSourceRelation(tableRef, sqlContext, options, Option(schema))
  }

  /**
   * Creates a new relation for a cassandra table given table, keyspace, cluster, push_down and schema
   * as parameters. It saves the data to the Cassandra table depends on [[SaveMode]]
   */
  override def createRelation(
    sqlContext: SQLContext,
    mode: SaveMode,
    parameters: Map[String, String],
    data: DataFrame): BaseRelation = {

    val (tableRef, options) = TableRefAndOptions(parameters)
    val table = CassandraSourceRelation(tableRef, sqlContext, options)

    mode match {
      case Append => table.insert(data, overwrite = false)
      case Overwrite => table.insert(data, overwrite = true)
      case ErrorIfExists =>
        if (table.buildScan().isEmpty()) {
          table.insert(data, overwrite = false)
        } else {
          throw new UnsupportedOperationException("'Writing to a non-empty Cassandra Table is not allowed.'")
        }
      case Ignore =>
        if (table.buildScan().isEmpty()) {
          table.insert(data, overwrite = false)
        }
    }

    CassandraSourceRelation(tableRef, sqlContext, options)
  }
}

/** Store data source options */
case class CassandraSourceOptions(
    pushdown: Boolean = true,
    readConf: Option[ReadConf] = None,
    writeConf: Option[WriteConf] = None,
    cassandraConConf: Option[CassandraConnectorConf] = None,
    tableSizeInBytes: Option[Long] = None)

object DefaultSource {
  val CassandraDataSourceTableNameProperty = "c_table"
  val CassandraDataSourceKeyspaceNameProperty = "keyspace"
  val CassandraDataSourceClusterNameProperty = "cluster"
  val CassandraDataSourceUserDefinedSchemaNameProperty = "schema"
  val CassandraDataSourcePushdownEnableProperty = "push_down"
  val CassandraDataSourceProviderPackageName = DefaultSource.getClass.getPackage.getName
  val CassandraDataSourceProviderClassName = CassandraDataSourceProviderPackageName + ".DefaultSource"


  /** Parse parameters into CassandraDataSourceOptions and TableRef object */
  def TableRefAndOptions(parameters: Map[String, String]) : (TableRef, CassandraSourceOptions) = {
    val tableName = parameters(CassandraDataSourceTableNameProperty)
    val keyspaceName = parameters(CassandraDataSourceKeyspaceNameProperty)
    val clusterName = parameters.get(CassandraDataSourceClusterNameProperty)
    val pushdown : Boolean = parameters.getOrElse(CassandraDataSourcePushdownEnableProperty, "true").toBoolean
    val readConf = buildReadConf(parameters)
    val writeConf = buildWriteConf(parameters)
    val cassandraConConf = buildConnConf(parameters)
    val tableSizeInBytes = getTableSizeInBytes(parameters)

    (TableRef(tableName, keyspaceName, clusterName),
      CassandraSourceOptions(pushdown, readConf, writeConf, cassandraConConf, tableSizeInBytes))
  }

  // Dot is not allowed in Options key for Spark SQL parsers, so convert . to _
  // Map converted property to origin property name
  // TODO check SPARK 1.4 it may be fixed
  private val readPropertiesMap : Map[String, String] = {
    ReadConf.Properties.map(prop => (prop.replace(".", "_"), prop)).toMap
  }

  /** Construct ReadConf from options */
  def buildReadConf(parameters: Map[String, String]) :(Option[ReadConf]) = {
    val hasReadSettings = hasSettings(ReadConf.Properties, parameters)
    if(hasReadSettings) {
      val conf = sparkConf(readPropertiesMap, parameters)
      Option(ReadConf.fromSparkConf(conf))
    } else {
      None
    }
  }

  private val writePropertiesMap : Map[String, String] = {
    WriteConf.Properties.map(prop => (prop.replace(".", "_"), prop)).toMap
  }

  /** Construct WriteConf from options */
  def buildWriteConf(parameters: Map[String, String]) :(Option[WriteConf]) = {
    val hasReadSettings = hasSettings(WriteConf.Properties, parameters)
    if(hasReadSettings) {
      val conf = sparkConf(writePropertiesMap, parameters)
      Option(WriteConf.fromSparkConf(conf))
    } else {
      None
    }
  }

  private val connPropertiesMap : Map[String, String] = {
    CassandraConnectorConf.Properties.map(prop => (prop.replace(".", "_"), prop)).toMap
  }

  /** Construct CassandraConnectorConf from options */
  def buildConnConf(parameters: Map[String, String]) :(Option[CassandraConnectorConf]) = {
    val hasConnSettings = hasSettings(CassandraConnectorConf.Properties, parameters)
    if(hasConnSettings) {
      val conf = sparkConf(connPropertiesMap, parameters)
      Option(CassandraConnectorConf(conf))
    } else {
      None
    }
  }

  /** Construct tableSizeInBytes from options */
  def getTableSizeInBytes(parameters: Map[String, String]) : Option[Long] = {
    val tableSizeInBytesProperty = CassandraSourceRelation.Properties.seq.head.replace(".", "_")
    parameters.get(tableSizeInBytesProperty).map(_.toLong)
  }

  /** Construct SparkConf from parameters and convertedPropertiesMap */
  private def sparkConf(convertedPropertiesMap: Map[String, String], parameters: Map[String, String]) : SparkConf = {
    val conf = new SparkConf()
    for (convertedProp <- readPropertiesMap.keySet) {
      val setting = parameters.get(convertedProp)
      if (setting.nonEmpty) {
        conf.set(readPropertiesMap(convertedProp), setting.get)
      }
    }
    conf
  }

  /** Check whether parameters have the conf settings */
  private def hasSettings(settings: Set[String] , parameters: Map[String, String]) : Boolean = {
    val tableSettings = parameters.keySet
    tableSettings.intersect(settings.map(_.replace(".", "_"))).nonEmpty
  }

  /** Check whether the provider is Cassandra datasource or not */
  def cassandraSource(provider: String) : Boolean = {
    provider == CassandraDataSourceProviderPackageName || provider == CassandraDataSourceProviderClassName
  }
}

