package auth.datalab.sequenceDetection

import auth.datalab.sequenceDetection.PairExtraction.{Indexing, Parsing, SkipTillAnyMatch, State, StrictContiguity, TimeCombinations, ZipCombinations}
import auth.datalab.sequenceDetection.Structs.InvertedOne
import org.apache.log4j.{Level, Logger}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.storage.StorageLevel
import org.apache.spark.util.SizeEstimator

import java.sql.Timestamp
import scala.collection.mutable

object SequenceDetectionBigData {
  private var cassandraConnection: CassandraConnection = null
  private var table_date = ""

  def main(args: Array[String]): Unit = {
    val fileName: String = args(0)
    val type_of_algorithm = args(1) //parsing, indexing or state
    val deleteAll = args(2)
    val join = args(3).toInt
    val deletePrevious = args(4)
    println(fileName, type_of_algorithm, deleteAll, join)


    Logger.getLogger("org").setLevel(Level.ERROR)

    //    var table_name = fileName.toLowerCase().split('.')(0).split('$')(0).replace(' ', '_')
    var table_name = ""
    if (args.length == 7) {
      table_name = fileName.split('/').last.toLowerCase().split('.')(0).split('$')(0).replace(' ', '_')
    } else if (args.length == 11) {
      table_name = fileName
    } else {
      println("Not acceptable number of parameters")
      System.exit(2)
    }
    var table_temp = table_name + "_temp"
    var table_seq = table_name + "_seq"
    var table_idx = table_name + "_idx"
    var table_count = table_name + "_count"
    var table_one = table_name + "_one"

    val tables: Map[String, String] = Map(
      table_idx -> "event1_name text, event2_name text, sequences list<text>, PRIMARY KEY (event1_name, event2_name)",
      table_temp -> "event1_name text, event2_name text,  sequences list<text>, PRIMARY KEY (event1_name, event2_name)",
      table_count -> "event1_name text, sequences_per_field list<text>, PRIMARY KEY (event1_name)",
      table_seq -> "sequence_id text, events list<text>, PRIMARY KEY (sequence_id)",
      table_one -> "event_name text, sequences list<text>, PRIMARY KEY (event_name)"
    )

    cassandraConnection = new CassandraConnection()
    cassandraConnection.startSpark()


    if (deletePrevious == "1") {
      cassandraConnection.dropTables(List(table_idx, table_seq, table_temp, table_count, table_one))
    }
    if (deleteAll == "1") {
      cassandraConnection.dropAlltables()
    }
    cassandraConnection.createTables(tables)


    try {
      val spark = SparkSession.builder().getOrCreate()

      val traceGenerator: TraceGenerator = new TraceGenerator(args(6).toInt, args(7).toInt, args(8).toInt, args(9).toInt)

      val allExecutors = spark.sparkContext.getExecutorMemoryStatus.keys.size
      val minExecutorMemory = spark.sparkContext.getExecutorMemoryStatus.map(_._2._1).min
      println(s"Number of executors= $allExecutors, with minimum memory=$minExecutorMemory")
      val traces = traceGenerator.numberOfTraces
      val average_length = (traceGenerator.minTraceSize+traceGenerator.maxTraceSize)/2
      val size_estimate_trace: scala.math.BigInt = SizeEstimator.estimate(traceGenerator.estimate_size().events.head) * average_length * (average_length / 2)
      //        each trace has approximate average_length events (each trace has size equal to size estimator)
      var partitionNumber = if (minExecutorMemory >= size_estimate_trace * traces) 0 else ((size_estimate_trace * traces) / minExecutorMemory).toInt + 1
      partitionNumber = partitionNumber / allExecutors + 1
      val ids = (1 to traces).toList.sliding((traces / partitionNumber), (traces / partitionNumber).toInt).toList
      println("Iterations: ", ids.length)

      var k = 0L
      for (id <- ids) {
        val sequencesRDD: RDD[Structs.Sequence] = traceGenerator.produce(id)
          .repartition(allExecutors)
        val start = System.currentTimeMillis()
        val invertedOneRDD: RDD[Structs.InvertedOne] = sequencesRDD.flatMap(x => {
          val id = x.sequence_id
          x.events.map(x => ((id, x.event), x))
        })
          .groupBy(_._1)
          .map(x => {
            val events = x._2.toList.map(_._2.timestamp).sortWith((a, b) => Utils.compareTimes(a, b))
            (x._1._2, Structs.IdTimeList(x._1._1, events))
          })
          .groupBy(_._1)
          .filter(_._2.toList.nonEmpty)
          .map(m => {
            InvertedOne(m._1, m._2.toList.map(_._2))
          })
        invertedOneRDD.persist(StorageLevel.MEMORY_AND_DISK)
        cassandraConnection.writeTableOne(invertedOneRDD, table_one)
        invertedOneRDD.unpersist()
        val sequenceCombinedRDD: RDD[Structs.Sequence] = this.combine_sequences(sequencesRDD, table_seq, cassandraConnection,
          "2018-01-01 00:00:00", 10)
        println("Finding Combinations ...")
        val combinationsRDD = startCombinationsRDD(sequenceCombinedRDD, table_temp, "", join, type_of_algorithm, table_seq,
          null, 0)
        val combinationsCountRDD = CountPairs.createCountCombinationsRDD(combinationsRDD)
        println("Writing combinations RDD to Cassandra ..")
        cassandraConnection.writeTableSequenceIndex(combinationsRDD, table_idx)
        cassandraConnection.writeTableSeqCount(combinationsCountRDD, table_count)
        if (join != 0) {
          cassandraConnection.writeTableSeqTemp(combinationsRDD, table_temp)
        }
        combinationsRDD.unpersist()


        cassandraConnection.writeTableSeq(sequenceCombinedRDD, table_seq)
        sequenceCombinedRDD.unpersist()
        sequencesRDD.unpersist()
        k = k + System.currentTimeMillis() - start
      }

      println(s"Time taken: ${k} ms")
      cassandraConnection.closeSpark()
      val mb = 1024 * 1024
      val runtime = Runtime.getRuntime
      println("ALL RESULTS IN MB")
      println("** Used Memory:  " + (runtime.totalMemory - runtime.freeMemory) / mb)
      println("** Free Memory:  " + runtime.freeMemory / mb)
      println("** Total Memory: " + runtime.totalMemory / mb)
      println("** Max Memory:   " + runtime.maxMemory / mb)
    } catch {
      case e: Exception => {
        e.getStackTrace.foreach(println)
        println(e.getMessage())
        cassandraConnection.closeSpark()
      }
    }
  }

  def createCombinationsRDD(seqRDD: RDD[Structs.Sequence], type_of_algorithm: String): RDD[Structs.EventIdTimeLists] = {
    type_of_algorithm match {
      case "parsing" => Parsing.extract(seqRDD)
      case "indexing" => Indexing.extract(seqRDD)
      case "state" => State.extract(seqRDD)
      case "strict" => StrictContiguity.extract(seqRDD)
      case "anymatch" => SkipTillAnyMatch.extract(seqRDD)
      case _ => throw new Exception("Wrong type of algorithm")
    }
  }

  /**
   * Method to create the RDD with the sequence of events for sequence identifier by combining
   * the new data along with the already written ones to cassandra
   *
   * @param table_name The table from which we will collect data
   * @param timestamp  The timestamp to help with the days it needs to look back into
   * @param look_back  The maximum days (along with the new one) that the sequence must hold
   * @return An RDD of [Sequence] class with the data ready to be written to cassandra
   */
  def combine_sequences(seq_log_RDD: RDD[Structs.Sequence], table_name: String, cassandraConnection: CassandraConnection, timestamp: String, look_back: Int): RDD[Structs.Sequence] = {
    //need to find them these days before
    val spark = SparkSession.builder().getOrCreate()
    import spark.implicits._
    seq_log_RDD.coalesce(spark.sparkContext.defaultParallelism)
    val funnel_time = Timestamp.valueOf(timestamp).getTime - (look_back * 24 * 3600 * 1000)
    val funnel_date = new Timestamp(funnel_time)
    val cassandraTable = cassandraConnection.readTable(table_name)
      .map(row => {
        val events = row
          .getAs[mutable.WrappedArray[String]](1)
          .toList
          .map(line => {
            val data = line
              .replace("Event(", "")
              .replace(")", "")
              .split(',')
            Structs.Event(data(0), data(1))
          })
          .filter(det => Utils.compareTimes(funnel_date.toString, det.timestamp)) //the events that are after the funnel time
        Structs.Sequence(events = events, sequence_id = row.getString(0).toLong)
      })
      .rdd
      .persist(StorageLevel.DISK_ONLY)
    cassandraTable.count()
    val res = this.mergeSeq(seq_log_RDD, cassandraTable)
      .coalesce(spark.sparkContext.defaultParallelism)
    res.count()
    cassandraTable.unpersist()
    res
  }

  /**
   * Method to merge 2 rdds of sequences
   *
   * @param newRdd The new sequence RDD
   * @param oldRdd The old sequence RDD
   * @return The merged RDD
   */
  def mergeSeq(newRdd: RDD[Structs.Sequence], oldRdd: RDD[Structs.Sequence]): RDD[Structs.Sequence] = {
    val tmp = oldRdd.union(newRdd)
    val finalCounts = tmp
      .keyBy(_.sequence_id)
      .reduceByKey((p1, p2) => {
        val newList = List.concat(p1.events, p2.events)
        Structs.Sequence(newList, p1.sequence_id)
      })
      .map(_._2)
    finalCounts

  }


  def startCombinationsRDD(seqRDD: RDD[Structs.Sequence], table_temp: String, time: String, join: Int, type_of_algorithm: String, table_name: String, entities: Broadcast[mutable.HashMap[Integer, Integer]], look_back_hours: Int): RDD[Structs.EventIdTimeLists] = {
    var res: RDD[Structs.EventIdTimeLists] = null
    if (join == 0) { // we have no prio knowledge and it will not have next
      res = createCombinationsRDD(seqRDD, type_of_algorithm)
      res = TimeCombinations.timeCombinationsRDD(res, time) // we need to eliminate all the pairs completed before the time
    } else {
      val funnel_time = Timestamp.valueOf("2000-01-01 00:00:00").getTime - (look_back_hours * 3600 * 1000)
      val funnel_date = new Timestamp(funnel_time)
      val tempTable: DataFrame = cassandraConnection.readTemp(table_temp, funnel_date)
      res = ZipCombinations.zipCombinationsRDD(seqRDD, tempTable, table_name, funnel_date)
    }
    res
  }

}
