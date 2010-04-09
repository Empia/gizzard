package com.twitter.gizzard.jobs

import com.twitter.xrayspecs.TimeConversions._
import net.lag.logging.Logger
import shards.{Busy, Shard, ShardDatabaseTimeoutException, ShardTimeoutException}
import nameserver.NameServer
import scheduler.JobScheduler
import nameserver._


object Copy {
  val MIN_COPY = 500
}

trait CopyFactory[S <: shards.Shard] extends ((Int, Int) => Copy[S])

trait CopyParser[S <: shards.Shard] extends jobs.UnboundJobParser[(NameServer[S], JobScheduler)] {
  def apply(attributes: Map[String, Any]): Copy[S]
}

abstract case class Copy[S <: Shard](sourceShardId: Int, destinationShardId: Int, var count: Int) extends UnboundJob[(NameServer[S], JobScheduler)] {
  private val log = Logger.get(getClass.getName)

  def toMap = Map("source_shard_id" -> sourceShardId, "destination_shard_id" -> destinationShardId, "count" -> count) ++ serialize

  def finish(nameServer: NameServer[S], scheduler: JobScheduler) {
    nameServer.markShardBusy(destinationShardId, Busy.Normal)
    log.info("Copying finished for (type %s) from %d to %d",
             getClass.getName.split("\\.").last, sourceShardId, destinationShardId)
  }

  def apply(environment: (NameServer[S], JobScheduler)) {
    val (nameServer, scheduler) = environment
    try {
      log.info("Copying shard block (type %s) from %d to %d: state=%s",
               getClass.getName.split("\\.").last, sourceShardId, destinationShardId, toMap)
      val sourceShard = nameServer.findShardById(sourceShardId)
      val destinationShard = nameServer.findShardById(destinationShardId)
      // do this on each iteration, so it happens in the queue and can be retried if the db is busy:
      nameServer.markShardBusy(destinationShardId, Busy.Busy)
      val nextJob = copyPage(sourceShard, destinationShard, count)
      nextJob match {
        case Some(job) => scheduler(job)
        case None => finish(nameServer, scheduler)
      }
    } catch {
      case e: NonExistentShard =>
        log.error("Shard block copy failed because one of the shards doesn't exist. Terminating the copy.")
      case e: ShardTimeoutException if (count > Copy.MIN_COPY) =>
        log.warning("Shard block copy timed out; trying a smaller block size.")
        count = count / 2
        scheduler(this)
      case e: ShardDatabaseTimeoutException =>
        log.warning("Shard block copy failed to get a database connection; retrying.")
        scheduler(this)
      case e: Exception =>
        log.warning("Shard block copy stopped due to exception: %s", e)
        throw e
    }
  }

  def copyPage(sourceShard: S, destinationShard: S, count: Int): Option[Copy[S]]
  def serialize: Map[String, Any]
}
