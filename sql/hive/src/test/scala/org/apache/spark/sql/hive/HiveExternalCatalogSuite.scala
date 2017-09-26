/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.hive

import java.net.URI

import org.apache.hadoop.conf.Configuration

import org.apache.spark.SparkConf
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.catalog._
import org.apache.spark.sql.execution.command.DDLUtils
import org.apache.spark.sql.hive.client.{HiveClient, IsolatedClientLoader}
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.Utils


/**
 * Test suite for the [[HiveExternalCatalog]].
 */
class HiveExternalCatalogSuite extends ExternalCatalogSuite {

  private val client: HiveClient = {
    val metaVersion = IsolatedClientLoader.hiveVersion(
      HiveUtils.HIVE_METASTORE_VERSION.defaultValue.get)
    new IsolatedClientLoader(
      version = metaVersion,
      sparkConf = new SparkConf(),
      hadoopConf = new Configuration(),
      config = HiveUtils.newTemporaryConfiguration(useInMemoryDerby = true),
      isolationOn = false,
      baseClassLoader = Utils.getContextOrSparkClassLoader
    ).createClient()
  }

  private val externalCatalog: HiveExternalCatalog = {
    val catalog = new HiveExternalCatalog(new SparkConf, new Configuration)
    catalog.client.reset()
    catalog
  }

  protected override val utils: CatalogTestUtils = new CatalogTestUtils {
    override val tableInputFormat: String = "org.apache.hadoop.mapred.SequenceFileInputFormat"
    override val tableOutputFormat: String = "org.apache.hadoop.mapred.SequenceFileOutputFormat"
    override def newEmptyCatalog(): ExternalCatalog = externalCatalog
    override val defaultProvider: String = "hive"
  }

  protected override def resetState(): Unit = {
    externalCatalog.client.reset()
  }

  import utils._

  test("SPARK-18647: do not put provider in table properties for Hive serde table") {
    val catalog = newBasicCatalog()
    val hiveTable = CatalogTable(
      identifier = TableIdentifier("hive_tbl", Some("db1")),
      tableType = CatalogTableType.MANAGED,
      storage = storageFormat,
      schema = new StructType().add("col1", "int").add("col2", "string"),
      provider = Some("hive"))
    catalog.createTable(hiveTable, ignoreIfExists = false)

    val rawTable = externalCatalog.client.getTable("db1", "hive_tbl")
    assert(!rawTable.properties.contains(HiveExternalCatalog.DATASOURCE_PROVIDER))
    assert(DDLUtils.isHiveTable(externalCatalog.getTable("db1", "hive_tbl")))
  }

  Seq("parquet", "hive").foreach { format =>
    test(s"Partition columns should be put at the end of table schema for the format $format") {
      val catalog = newBasicCatalog()
      val newSchema = new StructType()
        .add("col1", "int")
        .add("col2", "string")
        .add("partCol1", "int")
        .add("partCol2", "string")
      val table = CatalogTable(
        identifier = TableIdentifier("tbl", Some("db1")),
        tableType = CatalogTableType.MANAGED,
        storage = CatalogStorageFormat.empty,
        schema = new StructType()
          .add("col1", "int")
          .add("partCol1", "int")
          .add("partCol2", "string")
          .add("col2", "string"),
        provider = Some(format),
        partitionColumnNames = Seq("partCol1", "partCol2"))
      catalog.createTable(table, ignoreIfExists = false)

      val restoredTable = externalCatalog.getTable("db1", "tbl")
      assert(restoredTable.schema == newSchema)
    }
  }

  test("CDH-58542: auto-correct table location for namenode HA") {
    // set up a configuration with two nameservices (eg. two clusters, both with HA)
    val conf = new Configuration()
    conf.set("dfs.nameservices", "ns1,ns2")
    conf.set("dfs.ha.namenodes.ns1", "namenode1,namenode5")
    conf.set("dfs.namenode.rpc-address.ns1.namenode1", "foo-1.xyz.com:8020")
    conf.set("dfs.namenode.rpc-address.ns1.namenode5", "foo-2.xyz.com:1234")
    conf.set("dfs.ha.namenodes.ns2", "namenode17,namenode25")
    conf.set("dfs.namenode.rpc-address.ns2.namenode17", "blah-1.bar.com:8020")
    conf.set("dfs.namenode.rpc-address.ns2.namenode25", "blah-2.bar.com:8020")
    val namenodeToNameservice = HiveExternalCatalog.buildNamenodeToNameserviceMapping(conf)
    assert(namenodeToNameservice === Map(
      "hdfs://foo-1.xyz.com:8020" -> "hdfs://ns1",
      "hdfs://foo-2.xyz.com:1234" -> "hdfs://ns1",
      "hdfs://blah-1.bar.com:8020" -> "hdfs://ns2",
      "hdfs://blah-2.bar.com:8020" -> "hdfs://ns2"
    ))

    // go through a handful of paths, making sure the right substitions (or no substitution) is
    // applied.  If no port is given, we don't try to guess the port for making the substitution.
    Seq(
      "hdfs://foo-1.xyz.com:8020/" -> "hdfs://ns1/",
      "hdfs://foo-1.xyz.com:8020/some/path" -> "hdfs://ns1/some/path",
      "hdfs://foo-1.xyz.com:8021/some/path" -> "hdfs://foo-1.xyz.com:8021/some/path",
      "hdfs://foo-1.xyz.com/some/path" -> "hdfs://foo-1.xyz.com/some/path",
      "hdfs://foo-2.xyz.com:1234/some/path" -> "hdfs://ns1/some/path",
      "hdfs://blah-1.bar.com:8020/another/path" -> "hdfs://ns2/another/path",
      "hdfs://another.cluster.com:8020/my/path" -> "hdfs://another.cluster.com:8020/my/path",
      "file:/some/local/path/spark-warehouse" ->
        "file:/some/local/path/spark-warehouse",
      "/bare/path" -> "/bare/path"
    ).foreach { case (orig, exp) =>
      val convertedName = HiveExternalCatalog.convertNamenodeToNameservice(
          namenodeToNameservice,
          new URI(orig),
          "my_db")
      assert( convertedName === new URI(exp))
    }
  }
}
