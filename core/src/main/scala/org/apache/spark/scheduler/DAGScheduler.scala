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

package org.apache.spark.scheduler
import java.nio.file.{Files, Paths}             //* 
import java.io.{File, FileWriter}               // SSPARK
import scala.collection.mutable.LinkedHashMap   //*

import java.io.NotSerializableException
import java.util.Properties
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BiFunction

import scala.annotation.tailrec
import scala.collection.Map
import scala.collection.mutable.{ArrayStack, HashMap, HashSet}
import scala.concurrent.duration._
import scala.language.existentials
import scala.language.postfixOps
import scala.util.control.NonFatal

import org.apache.commons.lang3.SerializationUtils

import org.apache.spark._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config
import org.apache.spark.network.util.JavaUtils
import org.apache.spark.partial.{ApproximateActionListener, ApproximateEvaluator, PartialResult}
import org.apache.spark.rdd.{DeterministicLevel, RDD, RDDCheckpointData}
import org.apache.spark.rpc.RpcTimeout
import org.apache.spark.storage._
import org.apache.spark.storage.BlockManagerMessages.BlockManagerHeartbeat
import org.apache.spark.util._

/**
 * The high-level scheduling layer that implements stage-oriented scheduling. It computes a DAG of
 * stages for each job, keeps track of which RDDs and stage outputs are materialized, and finds a
 * minimal schedule to run the job. It then submits stages as TaskSets to an underlying
 * TaskScheduler implementation that runs them on the cluster. A TaskSet contains fully independent
 * tasks that can run right away based on the data that's already on the cluster (e.g. map output
 * files from previous stages), though it may fail if this data becomes unavailable.
 *
 * Spark stages are created by breaking the RDD graph at shuffle boundaries. RDD operations with
 * "narrow" dependencies, like map() and filter(), are pipelined together into one set of tasks
 * in each stage, but operations with shuffle dependencies require multiple stages (one to write a
 * set of map output files, and another to read those files after a barrier). In the end, every
 * stage will have only shuffle dependencies on other stages, and may compute multiple operations
 * inside it. The actual pipelining of these operations happens in the RDD.compute() functions of
 * various RDDs
 *
 * In addition to coming up with a DAG of stages, the DAGScheduler also determines the preferred
 * locations to run each task on, based on the current cache status, and passes these to the
 * low-level TaskScheduler. Furthermore, it handles failures due to shuffle output files being
 * lost, in which case old stages may need to be resubmitted. Failures *within* a stage that are
 * not caused by shuffle file loss are handled by the TaskScheduler, which will retry each task
 * a small number of times before cancelling the whole stage.
 *
 * When looking through this code, there are several key concepts:
 *
 *  - Jobs (represented by [[ActiveJob]]) are the top-level work items submitted to the scheduler.
 *    For example, when the user calls an action, like count(), a job will be submitted through
 *    submitJob. Each Job may require the execution of multiple stages to build intermediate data.
 *
 *  - Stages ([[Stage]]) are sets of tasks that compute intermediate results in jobs, where each
 *    task computes the same function on partitions of the same RDD. Stages are separated at shuffle
 *    boundaries, which introduce a barrier (where we must wait for the previous stage to finish to
 *    fetch outputs). There are two types of stages: [[ResultStage]], for the final stage that
 *    executes an action, and [[ShuffleMapStage]], which writes map output files for a shuffle.
 *    Stages are often shared across multiple jobs, if these jobs reuse the same RDDs.
 *
 *  - Tasks are individual units of work, each sent to one machine.
 *
 *  - Cache tracking: the DAGScheduler figures out which RDDs are cached to avoid recomputing them
 *    and likewise remembers which shuffle map stages have already produced output files to avoid
 *    redoing the map side of a shuffle.
 *
 *  - Preferred locations: the DAGScheduler also computes where to run each task in a stage based
 *    on the preferred locations of its underlying RDDs, or the location of cached or shuffle data.
 *
 *  - Cleanup: all data structures are cleared when the running jobs that depend on them finish,
 *    to prevent memory leaks in a long-running application.
 *
 * To recover from failures, the same stage might need to run multiple times, which are called
 * "attempts". If the TaskScheduler reports that a task failed because a map output file from a
 * previous stage was lost, the DAGScheduler resubmits that lost stage. This is detected through a
 * CompletionEvent with FetchFailed, or an ExecutorLost event. The DAGScheduler will wait a small
 * amount of time to see whether other nodes or tasks fail, then resubmit TaskSets for any lost
 * stage(s) that compute the missing tasks. As part of this process, we might also have to create
 * Stage objects for old (finished) stages where we previously cleaned up the Stage object. Since
 * tasks from the old attempt of a stage could still be running, care must be taken to map any
 * events received in the correct Stage object.
 *
 * Here's a checklist to use when making or reviewing changes to this class:
 *
 *  - All data structures should be cleared when the jobs involving them end to avoid indefinite
 *    accumulation of state in long-running programs.
 *
 *  - When adding a new data structure, update `DAGSchedulerSuite.assertDataStructuresEmpty` to
 *    include the new structure. This will help to catch memory leaks.
 */
private[spark] class DAGScheduler(
    private[scheduler] val sc: SparkContext,
    private[scheduler] val taskScheduler: TaskScheduler,
    listenerBus: LiveListenerBus,
    mapOutputTracker: MapOutputTrackerMaster,
    blockManagerMaster: BlockManagerMaster,
    env: SparkEnv,
    clock: Clock = new SystemClock())
  extends Logging {

  def this(sc: SparkContext, taskScheduler: TaskScheduler) = {
    this(
      sc,
      taskScheduler,
      sc.listenerBus,
      sc.env.mapOutputTracker.asInstanceOf[MapOutputTrackerMaster],
      sc.env.blockManager.master,
      sc.env)
  }

  def this(sc: SparkContext) = this(sc, sc.taskScheduler)

  private[spark] val metricsSource: DAGSchedulerSource = new DAGSchedulerSource(this)

  private[scheduler] val nextJobId = new AtomicInteger(0)
  private[scheduler] def numTotalJobs: Int = nextJobId.get()
  private val nextStageId = new AtomicInteger(0)

  private[scheduler] val jobIdToStageIds = new HashMap[Int, HashSet[Int]]
  private[scheduler] val stageIdToStage = new HashMap[Int, Stage]
  /**
   * Mapping from shuffle dependency ID to the ShuffleMapStage that will generate the data for
   * that dependency. Only includes stages that are part of currently running job (when the job(s)
   * that require the shuffle stage complete, the mapping will be removed, and the only record of
   * the shuffle data will be in the MapOutputTracker).
   */
  private[scheduler] val shuffleIdToMapStage = new HashMap[Int, ShuffleMapStage]
  private[scheduler] val jobIdToActiveJob = new HashMap[Int, ActiveJob]

  // Stages we need to run whose parents aren't done
  private[scheduler] val waitingStages = new HashSet[Stage]

  // Stages we are running right now
  private[scheduler] val runningStages = new HashSet[Stage]

  // Stages that must be resubmitted due to fetch failures
  private[scheduler] val failedStages = new HashSet[Stage]

  private[scheduler] val activeJobs = new HashSet[ActiveJob]

  /**
   * Contains the locations that each RDD's partitions are cached on.  This map's keys are RDD ids
   * and its values are arrays indexed by partition numbers. Each array value is the set of
   * locations where that RDD partition is cached.
   *
   * All accesses to this map should be guarded by synchronizing on it (see SPARK-4454).
   */
  private val cacheLocs = new HashMap[Int, IndexedSeq[Seq[TaskLocation]]]

  /**
   * Tracks the latest epoch of a fully processed error related to the given executor. (We use
   * the MapOutputTracker's epoch number, which is sent with every task.)
   *
   * When an executor fails, it can affect the results of many tasks, and we have to deal with
   * all of them consistently. We don't simply ignore all future results from that executor,
   * as the failures may have been transient; but we also don't want to "overreact" to follow-
   * on errors we receive. Furthermore, we might receive notification of a task success, after
   * we find out the executor has actually failed; we'll assume those successes are, in fact,
   * simply delayed notifications and the results have been lost, if the tasks started in the
   * same or an earlier epoch. In particular, we use this to control when we tell the
   * BlockManagerMaster that the BlockManager has been lost.
   */
  private val executorFailureEpoch = new HashMap[String, Long]

  /**
   * Tracks the latest epoch of a fully processed error where shuffle files have been lost from
   * the given executor.
   *
   * This is closely related to executorFailureEpoch. They only differ for the executor when
   * there is an external shuffle service serving shuffle files and we haven't been notified that
   * the entire worker has been lost. In that case, when an executor is lost, we do not update
   * the shuffleFileLostEpoch; we wait for a fetch failure. This way, if only the executor
   * fails, we do not unregister the shuffle data as it can still be served; but if there is
   * a failure in the shuffle service (resulting in fetch failure), we unregister the shuffle
   * data only once, even if we get many fetch failures.
   */
  private val shuffleFileLostEpoch = new HashMap[String, Long]

  private [scheduler] val outputCommitCoordinator = env.outputCommitCoordinator

  // A closure serializer that we reuse.
  // This is only safe because DAGScheduler runs in a single thread.
  private val closureSerializer = SparkEnv.get.closureSerializer.newInstance()

  /** If enabled, FetchFailed will not cause stage retry, in order to surface the problem. */
  private val disallowStageRetryForTest = sc.getConf.getBoolean("spark.test.noStageRetry", false)

  /**
   * Whether to unregister all the outputs on the host in condition that we receive a FetchFailure,
   * this is set default to false, which means, we only unregister the outputs related to the exact
   * executor(instead of the host) on a FetchFailure.
   */
  private[scheduler] val unRegisterOutputOnHostOnFetchFailure =
    sc.getConf.get(config.UNREGISTER_OUTPUT_ON_HOST_ON_FETCH_FAILURE)

  /**
   * Number of consecutive stage attempts allowed before a stage is aborted.
   */
  private[scheduler] val maxConsecutiveStageAttempts =
    sc.getConf.getInt("spark.stage.maxConsecutiveAttempts",
      DAGScheduler.DEFAULT_MAX_CONSECUTIVE_STAGE_ATTEMPTS)

  /**
   * Number of max concurrent tasks check failures for each barrier job.
   */
  private[scheduler] val barrierJobIdToNumTasksCheckFailures = new ConcurrentHashMap[Int, Int]

  /**
   * Time in seconds to wait between a max concurrent tasks check failure and the next check.
   */
  private val timeIntervalNumTasksCheck = sc.getConf
    .get(config.BARRIER_MAX_CONCURRENT_TASKS_CHECK_INTERVAL)

  /**
   * Max number of max concurrent tasks check failures allowed for a job before fail the job
   * submission.
   */
  private val maxFailureNumTasksCheck = sc.getConf
    .get(config.BARRIER_MAX_CONCURRENT_TASKS_CHECK_MAX_FAILURES)

  private val messageScheduler =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("dag-scheduler-message")

  private[spark] val eventProcessLoop = new DAGSchedulerEventProcessLoop(this)
  taskScheduler.setDAGScheduler(this)

  private val isSSparkLogEnabled = sc.getConf.getBoolean("spark.ssparkLog.enabled", false)
  private val isSSparkProfileEnabled = sc.getConf.getBoolean("spark.ssparkProfile.enabled", false)
  
  /**
   * Called by the TaskSetManager to report task's starting.
   */
  def taskStarted(task: Task[_], taskInfo: TaskInfo) {
    eventProcessLoop.post(BeginEvent(task, taskInfo))
  }

  /**
   * Called by the TaskSetManager to report that a task has completed
   * and results are being fetched remotely.
   */
  def taskGettingResult(taskInfo: TaskInfo) {
    eventProcessLoop.post(GettingResultEvent(taskInfo))
  }

  /**
   * Called by the TaskSetManager to report task completions or failures.
   */
  def taskEnded(
      task: Task[_],
      reason: TaskEndReason,
      result: Any,
      accumUpdates: Seq[AccumulatorV2[_, _]],
      taskInfo: TaskInfo): Unit = {
    eventProcessLoop.post(
      CompletionEvent(task, reason, result, accumUpdates, taskInfo))
  }

  /**
   * Update metrics for in-progress tasks and let the master know that the BlockManager is still
   * alive. Return true if the driver knows about the given block manager. Otherwise, return false,
   * indicating that the block manager should re-register.
   */
  def executorHeartbeatReceived(
      execId: String,
      // (taskId, stageId, stageAttemptId, accumUpdates)
      accumUpdates: Array[(Long, Int, Int, Seq[AccumulableInfo])],
      blockManagerId: BlockManagerId): Boolean = {
    listenerBus.post(SparkListenerExecutorMetricsUpdate(execId, accumUpdates))
    blockManagerMaster.driverEndpoint.askSync[Boolean](
      BlockManagerHeartbeat(blockManagerId), new RpcTimeout(600 seconds, "BlockManagerHeartbeat"))
  }

  /**
   * Called by TaskScheduler implementation when an executor fails.
   */
  def executorLost(execId: String, reason: ExecutorLossReason): Unit = {
    eventProcessLoop.post(ExecutorLost(execId, reason))
  }

  /**
   * Called by TaskScheduler implementation when a worker is removed.
   */
  def workerRemoved(workerId: String, host: String, message: String): Unit = {
    eventProcessLoop.post(WorkerRemoved(workerId, host, message))
  }

  /**
   * Called by TaskScheduler implementation when a host is added.
   */
  def executorAdded(execId: String, host: String): Unit = {
    eventProcessLoop.post(ExecutorAdded(execId, host))
  }

  /**
   * Called by the TaskSetManager to cancel an entire TaskSet due to either repeated failures or
   * cancellation of the job itself.
   */
  def taskSetFailed(taskSet: TaskSet, reason: String, exception: Option[Throwable]): Unit = {
    eventProcessLoop.post(TaskSetFailed(taskSet, reason, exception))
  }

  /**
   * Called by the TaskSetManager when it decides a speculative task is needed.
   */
  def speculativeTaskSubmitted(task: Task[_]): Unit = {
    eventProcessLoop.post(SpeculativeTaskSubmitted(task))
  }

  private[scheduler]
  def getCacheLocs(rdd: RDD[_]): IndexedSeq[Seq[TaskLocation]] = cacheLocs.synchronized {
    // Note: this doesn't use `getOrElse()` because this method is called O(num tasks) times
    if (!cacheLocs.contains(rdd.id)) {
      // Note: if the storage level is NONE, we don't need to get locations from block manager.
      val locs: IndexedSeq[Seq[TaskLocation]] = if (rdd.getStorageLevel == StorageLevel.NONE) {
        IndexedSeq.fill(rdd.partitions.length)(Nil)
      } else {
        val blockIds =
          rdd.partitions.indices.map(index => RDDBlockId(rdd.id, index)).toArray[BlockId]
        blockManagerMaster.getLocations(blockIds).map { bms =>
          bms.map(bm => TaskLocation(bm.host, bm.executorId))
        }
      }
      cacheLocs(rdd.id) = locs
    }
    cacheLocs(rdd.id)
  }

  private def clearCacheLocs(): Unit = cacheLocs.synchronized {
    cacheLocs.clear()
  }

  /**
   * Gets a shuffle map stage if one exists in shuffleIdToMapStage. Otherwise, if the
   * shuffle map stage doesn't already exist, this method will create the shuffle map stage in
   * addition to any missing ancestor shuffle map stages.
   */
  private def getOrCreateShuffleMapStage(
      shuffleDep: ShuffleDependency[_, _, _],
      firstJobId: Int): ShuffleMapStage = {
    shuffleIdToMapStage.get(shuffleDep.shuffleId) match {
      case Some(stage) =>
        stage

      case None =>
        // Create stages for all missing ancestor shuffle dependencies.
        getMissingAncestorShuffleDependencies(shuffleDep.rdd).foreach { dep =>
          // Even though getMissingAncestorShuffleDependencies only returns shuffle dependencies
          // that were not already in shuffleIdToMapStage, it's possible that by the time we
          // get to a particular dependency in the foreach loop, it's been added to
          // shuffleIdToMapStage by the stage creation process for an earlier dependency. See
          // SPARK-13902 for more information.
          if (!shuffleIdToMapStage.contains(dep.shuffleId)) {
            createShuffleMapStage(dep, firstJobId)
          }
        }
        // Finally, create a stage for the given shuffle dependency.
        createShuffleMapStage(shuffleDep, firstJobId)
    }
  }

  /**
   * Check to make sure we don't launch a barrier stage with unsupported RDD chain pattern. The
   * following patterns are not supported:
   * 1. Ancestor RDDs that have different number of partitions from the resulting RDD (eg.
   * union()/coalesce()/first()/take()/PartitionPruningRDD);
   * 2. An RDD that depends on multiple barrier RDDs (eg. barrierRdd1.zip(barrierRdd2)).
   */
  private def checkBarrierStageWithRDDChainPattern(rdd: RDD[_], numTasksInStage: Int): Unit = {
    val predicate: RDD[_] => Boolean = (r =>
      r.getNumPartitions == numTasksInStage && r.dependencies.filter(_.rdd.isBarrier()).size <= 1)
    if (rdd.isBarrier() && !traverseParentRDDsWithinStage(rdd, predicate)) {
      throw new BarrierJobUnsupportedRDDChainException
    }
  }

  /**
   * Creates a ShuffleMapStage that generates the given shuffle dependency's partitions. If a
   * previously run stage generated the same shuffle data, this function will copy the output
   * locations that are still available from the previous shuffle to avoid unnecessarily
   * regenerating data.
   */
  def createShuffleMapStage(shuffleDep: ShuffleDependency[_, _, _], jobId: Int): ShuffleMapStage = {
    val rdd = shuffleDep.rdd
    checkBarrierStageWithDynamicAllocation(rdd)
    checkBarrierStageWithNumSlots(rdd)
    checkBarrierStageWithRDDChainPattern(rdd, rdd.getNumPartitions)
    val numTasks = rdd.partitions.length
    val parents = getOrCreateParentStages(rdd, jobId)
    val id = nextStageId.getAndIncrement()
    val stage = new ShuffleMapStage(
      id, rdd, numTasks, parents, jobId, rdd.creationSite, shuffleDep, mapOutputTracker)

    stageIdToStage(id) = stage
    shuffleIdToMapStage(shuffleDep.shuffleId) = stage
    updateJobIdStageIdMaps(jobId, stage)

    if (!mapOutputTracker.containsShuffle(shuffleDep.shuffleId)) {
      // Kind of ugly: need to register RDDs with the cache and map output tracker here
      // since we can't do it in the RDD constructor because # of partitions is unknown
      logInfo(s"Registering RDD ${rdd.id} (${rdd.getCreationSite}) as input to " +
        s"shuffle ${shuffleDep.shuffleId}")
      mapOutputTracker.registerShuffle(shuffleDep.shuffleId, rdd.partitions.length)
    }
    stage
  }

  /**
   * We don't support run a barrier stage with dynamic resource allocation enabled, it shall lead
   * to some confusing behaviors (eg. with dynamic resource allocation enabled, it may happen that
   * we acquire some executors (but not enough to launch all the tasks in a barrier stage) and
   * later release them due to executor idle time expire, and then acquire again).
   *
   * We perform the check on job submit and fail fast if running a barrier stage with dynamic
   * resource allocation enabled.
   *
   * TODO SPARK-24942 Improve cluster resource management with jobs containing barrier stage
   */
  private def checkBarrierStageWithDynamicAllocation(rdd: RDD[_]): Unit = {
    if (rdd.isBarrier() && Utils.isDynamicAllocationEnabled(sc.getConf)) {
      throw new BarrierJobRunWithDynamicAllocationException
    }
  }

  /**
   * Check whether the barrier stage requires more slots (to be able to launch all tasks in the
   * barrier stage together) than the total number of active slots currently. Fail current check
   * if trying to submit a barrier stage that requires more slots than current total number. If
   * the check fails consecutively beyond a configured number for a job, then fail current job
   * submission.
   */
  private def checkBarrierStageWithNumSlots(rdd: RDD[_]): Unit = {
    if (rdd.isBarrier() && rdd.getNumPartitions > sc.maxNumConcurrentTasks) {
      throw new BarrierJobSlotsNumberCheckFailed
    }
  }

  /**
   * Create a ResultStage associated with the provided jobId.
   */
  private def createResultStage(
      rdd: RDD[_],
      func: (TaskContext, Iterator[_]) => _,
      partitions: Array[Int],
      jobId: Int,
      callSite: CallSite): ResultStage = {
    checkBarrierStageWithDynamicAllocation(rdd)
    checkBarrierStageWithNumSlots(rdd)
    checkBarrierStageWithRDDChainPattern(rdd, partitions.toSet.size)
    val parents = getOrCreateParentStages(rdd, jobId)
    val id = nextStageId.getAndIncrement()
    val stage = new ResultStage(id, rdd, func, partitions, parents, jobId, callSite)
    stageIdToStage(id) = stage
    updateJobIdStageIdMaps(jobId, stage)
    stage
  }

  /**
   * Get or create the list of parent stages for a given RDD.  The new Stages will be created with
   * the provided firstJobId.
   */
  private def getOrCreateParentStages(rdd: RDD[_], firstJobId: Int): List[Stage] = {
    getShuffleDependencies(rdd).map { shuffleDep =>
      getOrCreateShuffleMapStage(shuffleDep, firstJobId)
    }.toList
  }

  /** Find ancestor shuffle dependencies that are not registered in shuffleToMapStage yet */
  private def getMissingAncestorShuffleDependencies(
      rdd: RDD[_]): ArrayStack[ShuffleDependency[_, _, _]] = {
    val ancestors = new ArrayStack[ShuffleDependency[_, _, _]]
    val visited = new HashSet[RDD[_]]
    // We are manually maintaining a stack here to prevent StackOverflowError
    // caused by recursively visiting
    val waitingForVisit = new ArrayStack[RDD[_]]
    waitingForVisit.push(rdd)
    while (waitingForVisit.nonEmpty) {
      val toVisit = waitingForVisit.pop()
      if (!visited(toVisit)) {
        visited += toVisit
        getShuffleDependencies(toVisit).foreach { shuffleDep =>
          if (!shuffleIdToMapStage.contains(shuffleDep.shuffleId)) {
            ancestors.push(shuffleDep)
            waitingForVisit.push(shuffleDep.rdd)
          } // Otherwise, the dependency and its ancestors have already been registered.
        }
      }
    }
    ancestors
  }

  /**
   * Returns shuffle dependencies that are immediate parents of the given RDD.
   *
   * This function will not return more distant ancestors.  For example, if C has a shuffle
   * dependency on B which has a shuffle dependency on A:
   *
   * A <-- B <-- C
   *
   * calling this function with rdd C will only return the B <-- C dependency.
   *
   * This function is scheduler-visible for the purpose of unit testing.
   */
  private[scheduler] def getShuffleDependencies(
      rdd: RDD[_]): HashSet[ShuffleDependency[_, _, _]] = {
    val parents = new HashSet[ShuffleDependency[_, _, _]]
    val visited = new HashSet[RDD[_]]
    val waitingForVisit = new ArrayStack[RDD[_]]
    waitingForVisit.push(rdd)
    while (waitingForVisit.nonEmpty) {
      val toVisit = waitingForVisit.pop()
      if (!visited(toVisit)) {
        visited += toVisit
        toVisit.dependencies.foreach {
          case shuffleDep: ShuffleDependency[_, _, _] =>
            parents += shuffleDep
          case dependency =>
            waitingForVisit.push(dependency.rdd)
        }
      }
    }
    parents
  }

  /**
   * Traverses the given RDD and its ancestors within the same stage and checks whether all of the
   * RDDs satisfy a given predicate.
   */
  private def traverseParentRDDsWithinStage(rdd: RDD[_], predicate: RDD[_] => Boolean): Boolean = {
    val visited = new HashSet[RDD[_]]
    val waitingForVisit = new ArrayStack[RDD[_]]
    waitingForVisit.push(rdd)
    while (waitingForVisit.nonEmpty) {
      val toVisit = waitingForVisit.pop()
      if (!visited(toVisit)) {
        if (!predicate(toVisit)) {
          return false
        }
        visited += toVisit
        toVisit.dependencies.foreach {
          case _: ShuffleDependency[_, _, _] =>
            // Not within the same stage with current rdd, do nothing.
          case dependency =>
            waitingForVisit.push(dependency.rdd)
        }
      }
    }
    true
  }

  private def getMissingParentStages(stage: Stage): List[Stage] = {
    val missing = new HashSet[Stage]
    val visited = new HashSet[RDD[_]]
    // We are manually maintaining a stack here to prevent StackOverflowError
    // caused by recursively visiting
    val waitingForVisit = new ArrayStack[RDD[_]]
    def visit(rdd: RDD[_]) {
      if (!visited(rdd)) {
        visited += rdd
        val rddHasUncachedPartitions = getCacheLocs(rdd).contains(Nil)
        if (rddHasUncachedPartitions) {
          for (dep <- rdd.dependencies) {
            dep match {
              case shufDep: ShuffleDependency[_, _, _] =>
                val mapStage = getOrCreateShuffleMapStage(shufDep, stage.firstJobId)
                if (!mapStage.isAvailable) {
                  missing += mapStage
                }
              case narrowDep: NarrowDependency[_] =>
                waitingForVisit.push(narrowDep.rdd)
            }
          }
        }
      }
    }
    waitingForVisit.push(stage.rdd)
    while (waitingForVisit.nonEmpty) {
      visit(waitingForVisit.pop())
    }
    missing.toList
  }

  /**
   * Registers the given jobId among the jobs that need the given stage and
   * all of that stage's ancestors.
   */
  private def updateJobIdStageIdMaps(jobId: Int, stage: Stage): Unit = {
    @tailrec
    def updateJobIdStageIdMapsList(stages: List[Stage]) {
      if (stages.nonEmpty) {
        val s = stages.head
        s.jobIds += jobId
        jobIdToStageIds.getOrElseUpdate(jobId, new HashSet[Int]()) += s.id
        val parentsWithoutThisJobId = s.parents.filter { ! _.jobIds.contains(jobId) }
        updateJobIdStageIdMapsList(parentsWithoutThisJobId ++ stages.tail)
      }
    }
    updateJobIdStageIdMapsList(List(stage))
  }

  /**
   * Removes state for job and any stages that are not needed by any other job.  Does not
   * handle cancelling tasks or notifying the SparkListener about finished jobs/stages/tasks.
   *
   * @param job The job whose state to cleanup.
   */
  private def cleanupStateForJobAndIndependentStages(job: ActiveJob): Unit = {
    val registeredStages = jobIdToStageIds.get(job.jobId)
    if (registeredStages.isEmpty || registeredStages.get.isEmpty) {
      logError("No stages registered for job " + job.jobId)
    } else {
      stageIdToStage.filterKeys(stageId => registeredStages.get.contains(stageId)).foreach {
        case (stageId, stage) =>
          val jobSet = stage.jobIds
          if (!jobSet.contains(job.jobId)) {
            logError(
              "Job %d not registered for stage %d even though that stage was registered for the job"
              .format(job.jobId, stageId))
          } else {
            def removeStage(stageId: Int) {
              // data structures based on Stage
              for (stage <- stageIdToStage.get(stageId)) {
                if (runningStages.contains(stage)) {
                  logDebug("Removing running stage %d".format(stageId))
                  runningStages -= stage
                }
                for ((k, v) <- shuffleIdToMapStage.find(_._2 == stage)) {
                  shuffleIdToMapStage.remove(k)
                }
                if (waitingStages.contains(stage)) {
                  logDebug("Removing stage %d from waiting set.".format(stageId))
                  waitingStages -= stage
                }
                if (failedStages.contains(stage)) {
                  logDebug("Removing stage %d from failed set.".format(stageId))
                  failedStages -= stage
                }
              }
              // data structures based on StageId
              stageIdToStage -= stageId
              logDebug("After removal of stage %d, remaining stages = %d"
                .format(stageId, stageIdToStage.size))
            }

            jobSet -= job.jobId
            if (jobSet.isEmpty) { // no other job needs this stage
              removeStage(stageId)
            }
          }
      }
    }
    jobIdToStageIds -= job.jobId
    jobIdToActiveJob -= job.jobId
    activeJobs -= job
    job.finalStage match {
      case r: ResultStage => r.removeActiveJob()
      case m: ShuffleMapStage => m.removeActiveJob(job)
    }
  }

  /**
   * Submit an action job to the scheduler.
   *
   * @param rdd target RDD to run tasks on
   * @param func a function to run on each partition of the RDD
   * @param partitions set of partitions to run on; some jobs may not want to compute on all
   *   partitions of the target RDD, e.g. for operations like first()
   * @param callSite where in the user program this job was called
   * @param resultHandler callback to pass each result to
   * @param properties scheduler properties to attach to this job, e.g. fair scheduler pool name
   *
   * @return a JobWaiter object that can be used to block until the job finishes executing
   *         or can be used to cancel the job.
   *
   * @throws IllegalArgumentException when partitions ids are illegal
   */
  def submitJob[T, U](
      rdd: RDD[T],
      func: (TaskContext, Iterator[T]) => U,
      partitions: Seq[Int],
      callSite: CallSite,
      resultHandler: (Int, U) => Unit,
      properties: Properties): JobWaiter[U] = {
    // Check to make sure we are not launching a task on a partition that does not exist.
    val maxPartitions = rdd.partitions.length
    partitions.find(p => p >= maxPartitions || p < 0).foreach { p =>
      throw new IllegalArgumentException(
        "Attempting to access a non-existent partition: " + p + ". " +
          "Total number of partitions: " + maxPartitions)
    }

    val jobId = nextJobId.getAndIncrement()
    if (partitions.size == 0) {
      // Return immediately if the job is running 0 tasks
      return new JobWaiter[U](this, jobId, 0, resultHandler)
    }

    assert(partitions.size > 0)
    val func2 = func.asInstanceOf[(TaskContext, Iterator[_]) => _]
    val waiter = new JobWaiter(this, jobId, partitions.size, resultHandler)
    eventProcessLoop.post(JobSubmitted(
      jobId, rdd, func2, partitions.toArray, callSite, waiter,
      SerializationUtils.clone(properties)))
    waiter
  }

  /**
   * Run an action job on the given RDD and pass all the results to the resultHandler function as
   * they arrive.
   *
   * @param rdd target RDD to run tasks on
   * @param func a function to run on each partition of the RDD
   * @param partitions set of partitions to run on; some jobs may not want to compute on all
   *   partitions of the target RDD, e.g. for operations like first()
   * @param callSite where in the user program this job was called
   * @param resultHandler callback to pass each result to
   * @param properties scheduler properties to attach to this job, e.g. fair scheduler pool name
   *
   * @note Throws `Exception` when the job fails
   */
  def runJob[T, U](
      rdd: RDD[T],
      func: (TaskContext, Iterator[T]) => U,
      partitions: Seq[Int],
      callSite: CallSite,
      resultHandler: (Int, U) => Unit,
      properties: Properties): Unit = {
    val start = System.nanoTime
    val waiter = submitJob(rdd, func, partitions, callSite, resultHandler, properties)
    ThreadUtils.awaitReady(waiter.completionFuture, Duration.Inf)
    waiter.completionFuture.value.get match {
      case scala.util.Success(_) =>
        logInfo("Job %d finished: %s, took %f s".format
          (waiter.jobId, callSite.shortForm, (System.nanoTime - start) / 1e9))
      case scala.util.Failure(exception) =>
        logInfo("Job %d failed: %s, took %f s".format
          (waiter.jobId, callSite.shortForm, (System.nanoTime - start) / 1e9))
        // SPARK-8644: Include user stack trace in exceptions coming from DAGScheduler.
        val callerStackTrace = Thread.currentThread().getStackTrace.tail
        exception.setStackTrace(exception.getStackTrace ++ callerStackTrace)
        throw exception
    }
  }

  /**
   * Run an approximate job on the given RDD and pass all the results to an ApproximateEvaluator
   * as they arrive. Returns a partial result object from the evaluator.
   *
   * @param rdd target RDD to run tasks on
   * @param func a function to run on each partition of the RDD
   * @param evaluator `ApproximateEvaluator` to receive the partial results
   * @param callSite where in the user program this job was called
   * @param timeout maximum time to wait for the job, in milliseconds
   * @param properties scheduler properties to attach to this job, e.g. fair scheduler pool name
   */
  def runApproximateJob[T, U, R](
      rdd: RDD[T],
      func: (TaskContext, Iterator[T]) => U,
      evaluator: ApproximateEvaluator[U, R],
      callSite: CallSite,
      timeout: Long,
      properties: Properties): PartialResult[R] = {
    val listener = new ApproximateActionListener(rdd, func, evaluator, timeout)
    val func2 = func.asInstanceOf[(TaskContext, Iterator[_]) => _]
    val partitions = (0 until rdd.partitions.length).toArray
    val jobId = nextJobId.getAndIncrement()
    eventProcessLoop.post(JobSubmitted(
      jobId, rdd, func2, partitions, callSite, listener, SerializationUtils.clone(properties)))
    listener.awaitResult()    // Will throw an exception if the job fails
  }

  /**
   * Submit a shuffle map stage to run independently and get a JobWaiter object back. The waiter
   * can be used to block until the job finishes executing or can be used to cancel the job.
   * This method is used for adaptive query planning, to run map stages and look at statistics
   * about their outputs before submitting downstream stages.
   *
   * @param dependency the ShuffleDependency to run a map stage for
   * @param callback function called with the result of the job, which in this case will be a
   *   single MapOutputStatistics object showing how much data was produced for each partition
   * @param callSite where in the user program this job was submitted
   * @param properties scheduler properties to attach to this job, e.g. fair scheduler pool name
   */
  def submitMapStage[K, V, C](
      dependency: ShuffleDependency[K, V, C],
      callback: MapOutputStatistics => Unit,
      callSite: CallSite,
      properties: Properties): JobWaiter[MapOutputStatistics] = {

    val rdd = dependency.rdd
    val jobId = nextJobId.getAndIncrement()
    if (rdd.partitions.length == 0) {
      throw new SparkException("Can't run submitMapStage on RDD with 0 partitions")
    }

    // We create a JobWaiter with only one "task", which will be marked as complete when the whole
    // map stage has completed, and will be passed the MapOutputStatistics for that stage.
    // This makes it easier to avoid race conditions between the user code and the map output
    // tracker that might result if we told the user the stage had finished, but then they queries
    // the map output tracker and some node failures had caused the output statistics to be lost.
    val waiter = new JobWaiter(this, jobId, 1, (i: Int, r: MapOutputStatistics) => callback(r))
    eventProcessLoop.post(MapStageSubmitted(
      jobId, dependency, callSite, waiter, SerializationUtils.clone(properties)))
    waiter
  }

  /**
   * Cancel a job that is running or waiting in the queue.
   */
  def cancelJob(jobId: Int, reason: Option[String]): Unit = {
    logInfo("Asked to cancel job " + jobId)
    eventProcessLoop.post(JobCancelled(jobId, reason))
  }

  /**
   * Cancel all jobs in the given job group ID.
   */
  def cancelJobGroup(groupId: String): Unit = {
    logInfo("Asked to cancel job group " + groupId)
    eventProcessLoop.post(JobGroupCancelled(groupId))
  }

  /**
   * Cancel all jobs that are running or waiting in the queue.
   */
  def cancelAllJobs(): Unit = {
    eventProcessLoop.post(AllJobsCancelled)
  }

  private[scheduler] def doCancelAllJobs() {
    // Cancel all running jobs.
    runningStages.map(_.firstJobId).foreach(handleJobCancellation(_,
      Option("as part of cancellation of all jobs")))
    activeJobs.clear() // These should already be empty by this point,
    jobIdToActiveJob.clear() // but just in case we lost track of some jobs...
  }

  /**
   * Cancel all jobs associated with a running or scheduled stage.
   */
  def cancelStage(stageId: Int, reason: Option[String]) {
    eventProcessLoop.post(StageCancelled(stageId, reason))
  }

  /**
   * Kill a given task. It will be retried.
   *
   * @return Whether the task was successfully killed.
   */
  def killTaskAttempt(taskId: Long, interruptThread: Boolean, reason: String): Boolean = {
    taskScheduler.killTaskAttempt(taskId, interruptThread, reason)
  }

  /**
   * Resubmit any failed stages. Ordinarily called after a small amount of time has passed since
   * the last fetch failure.
   */
  private[scheduler] def resubmitFailedStages() {
    if (failedStages.size > 0) {
      // Failed stages may be removed by job cancellation, so failed might be empty even if
      // the ResubmitFailedStages event has been scheduled.
      logInfo("Resubmitting failed stages")
      clearCacheLocs()
      val failedStagesCopy = failedStages.toArray
      failedStages.clear()
      for (stage <- failedStagesCopy.sortBy(_.firstJobId)) {
        submitStage(stage)
      }
    }
  }

  /**
   * Check for waiting stages which are now eligible for resubmission.
   * Submits stages that depend on the given parent stage. Called when the parent stage completes
   * successfully.
   */
  private def submitWaitingChildStages(parent: Stage) {
    logTrace(s"Checking if any dependencies of $parent are now runnable")
    logTrace("running: " + runningStages)
    logTrace("waiting: " + waitingStages)
    logTrace("failed: " + failedStages)
    val childStages = waitingStages.filter(_.parents.contains(parent)).toArray
    waitingStages --= childStages
    for (stage <- childStages.sortBy(_.firstJobId)) {
      submitStage(stage)
    }
  }

  /** Finds the earliest-created active job that needs the stage */
  // TODO: Probably should actually find among the active jobs that need this
  // stage the one with the highest priority (highest-priority pool, earliest created).
  // That should take care of at least part of the priority inversion problem with
  // cross-job dependencies.
  private def activeJobForStage(stage: Stage): Option[Int] = {
    val jobsThatUseStage: Array[Int] = stage.jobIds.toArray.sorted
    jobsThatUseStage.find(jobIdToActiveJob.contains)
  }

  private[scheduler] def handleJobGroupCancelled(groupId: String) {
    // Cancel all jobs belonging to this job group.
    // First finds all active jobs with this group id, and then kill stages for them.
    val activeInGroup = activeJobs.filter { activeJob =>
      Option(activeJob.properties).exists {
        _.getProperty(SparkContext.SPARK_JOB_GROUP_ID) == groupId
      }
    }
    val jobIds = activeInGroup.map(_.jobId)
    jobIds.foreach(handleJobCancellation(_,
        Option("part of cancelled job group %s".format(groupId))))
  }

  private[scheduler] def handleBeginEvent(task: Task[_], taskInfo: TaskInfo) {
    // Note that there is a chance that this task is launched after the stage is cancelled.
    // In that case, we wouldn't have the stage anymore in stageIdToStage.
    val stageAttemptId =
      stageIdToStage.get(task.stageId).map(_.latestInfo.attemptNumber).getOrElse(-1)
    listenerBus.post(SparkListenerTaskStart(task.stageId, stageAttemptId, taskInfo))
  }

  private[scheduler] def handleSpeculativeTaskSubmitted(task: Task[_]): Unit = {
    listenerBus.post(SparkListenerSpeculativeTaskSubmitted(task.stageId))
  }

  private[scheduler] def handleTaskSetFailed(
      taskSet: TaskSet,
      reason: String,
      exception: Option[Throwable]): Unit = {
    stageIdToStage.get(taskSet.stageId).foreach { abortStage(_, reason, exception) }
  }

  private[scheduler] def cleanUpAfterSchedulerStop() {
    for (job <- activeJobs) {
      val error =
        new SparkException(s"Job ${job.jobId} cancelled because SparkContext was shut down")
      job.listener.jobFailed(error)
      // Tell the listeners that all of the running stages have ended.  Don't bother
      // cancelling the stages because if the DAG scheduler is stopped, the entire application
      // is in the process of getting stopped.
      val stageFailedMessage = "Stage cancelled because SparkContext was shut down"
      // The `toArray` here is necessary so that we don't iterate over `runningStages` while
      // mutating it.
      runningStages.toArray.foreach { stage =>
        markStageAsFinished(stage, Some(stageFailedMessage))
      }
      listenerBus.post(SparkListenerJobEnd(job.jobId, clock.getTimeMillis(), JobFailed(error)))
    }
  }

  private[scheduler] def handleGetTaskResult(taskInfo: TaskInfo) {
    listenerBus.post(SparkListenerTaskGettingResult(taskInfo))
  }

  private[scheduler] def handleJobSubmitted(jobId: Int,
      finalRDD: RDD[_],
      func: (TaskContext, Iterator[_]) => _,
      partitions: Array[Int],
      callSite: CallSite,
      listener: JobListener,
      properties: Properties) {
    var finalStage: ResultStage = null
    try {
      // New stage creation may throw an exception if, for example, jobs are run on a
      // HadoopRDD whose underlying HDFS files have been deleted.
      finalStage = createResultStage(finalRDD, func, partitions, jobId, callSite)
    } catch {
      case e: BarrierJobSlotsNumberCheckFailed =>
        logWarning(s"The job $jobId requires to run a barrier stage that requires more slots " +
          "than the total number of slots in the cluster currently.")
        // If jobId doesn't exist in the map, Scala coverts its value null to 0: Int automatically.
        val numCheckFailures = barrierJobIdToNumTasksCheckFailures.compute(jobId,
          new BiFunction[Int, Int, Int] {
            override def apply(key: Int, value: Int): Int = value + 1
          })
        if (numCheckFailures <= maxFailureNumTasksCheck) {
          messageScheduler.schedule(
            new Runnable {
              override def run(): Unit = eventProcessLoop.post(JobSubmitted(jobId, finalRDD, func,
                partitions, callSite, listener, properties))
            },
            timeIntervalNumTasksCheck,
            TimeUnit.SECONDS
          )
          return
        } else {
          // Job failed, clear internal data.
          barrierJobIdToNumTasksCheckFailures.remove(jobId)
          listener.jobFailed(e)
          return
        }

      case e: Exception =>
        logWarning("Creating new stage failed due to exception - job: " + jobId, e)
        listener.jobFailed(e)
        return
    }
    // Job submitted, clear internal data.
    barrierJobIdToNumTasksCheckFailures.remove(jobId)

    val job = new ActiveJob(jobId, finalStage, callSite, listener, properties)
    clearCacheLocs()
    logInfo("Got job %s (%s) with %d output partitions".format(
      job.jobId, callSite.shortForm, partitions.length))
    logInfo("Final stage: " + finalStage + " (" + finalStage.name + ")")
    logInfo("Parents of final stage: " + finalStage.parents)
    logInfo("Missing parents: " + getMissingParentStages(finalStage))

    val jobSubmissionTime = clock.getTimeMillis()
    jobIdToActiveJob(jobId) = job
    activeJobs += job
    finalStage.setActiveJob(job)
    val stageIds = jobIdToStageIds(jobId).toArray
    val stageInfos = stageIds.flatMap(id => stageIdToStage.get(id).map(_.latestInfo))
    listenerBus.post(
      SparkListenerJobStart(job.jobId, jobSubmissionTime, stageInfos, properties))
    submitStage(finalStage)
  }

  private[scheduler] def handleMapStageSubmitted(jobId: Int,
      dependency: ShuffleDependency[_, _, _],
      callSite: CallSite,
      listener: JobListener,
      properties: Properties) {
    // Submitting this map stage might still require the creation of some parent stages, so make
    // sure that happens.
    var finalStage: ShuffleMapStage = null
    try {
      // New stage creation may throw an exception if, for example, jobs are run on a
      // HadoopRDD whose underlying HDFS files have been deleted.
      finalStage = getOrCreateShuffleMapStage(dependency, jobId)
    } catch {
      case e: Exception =>
        logWarning("Creating new stage failed due to exception - job: " + jobId, e)
        listener.jobFailed(e)
        return
    }

    val job = new ActiveJob(jobId, finalStage, callSite, listener, properties)
    clearCacheLocs()
    logInfo("Got map stage job %s (%s) with %d output partitions".format(
      jobId, callSite.shortForm, dependency.rdd.partitions.length))
    logInfo("Final stage: " + finalStage + " (" + finalStage.name + ")")
    logInfo("Parents of final stage: " + finalStage.parents)
    logInfo("Missing parents: " + getMissingParentStages(finalStage))

    val jobSubmissionTime = clock.getTimeMillis()
    jobIdToActiveJob(jobId) = job
    activeJobs += job
    finalStage.addActiveJob(job)
    val stageIds = jobIdToStageIds(jobId).toArray
    val stageInfos = stageIds.flatMap(id => stageIdToStage.get(id).map(_.latestInfo))
    listenerBus.post(
      SparkListenerJobStart(job.jobId, jobSubmissionTime, stageInfos, properties))
    submitStage(finalStage)

    // If the whole stage has already finished, tell the listener and remove it
    if (finalStage.isAvailable) {
      markMapStageJobAsFinished(job, mapOutputTracker.getStatistics(dependency))
    }
  }

  var selectCandidatesThread: Option[Thread] = None

  /** Submits stage, but first recursively submits any missing parents. */
  private def submitStage(stage: Stage) {
    val jobId = activeJobForStage(stage)
    if (jobId.isDefined) {
      logDebug(s"submitStage($stage (name=${stage.name};" +
        s"jobs=${stage.jobIds.toSeq.sorted.mkString(",")}))")
      if (!waitingStages(stage) && !runningStages(stage) && !failedStages(stage)) {
        val missing = getMissingParentStages(stage).sortBy(_.id)
        logDebug("missing: " + missing)
        if (missing.isEmpty) {
          if (isSSparkProfileEnabled) 
            sc.makeLineage(jobId.get, stage.id, stage.rdd)  // SSPARK: make lineage on each Stage
                    
          if (sc.isSSparkOptimizeEnabled) {
            val lineage = sc.getLineage(stage.id)
            val rootLineage = sc.rootLineage.last
            val (stageInputBytes, startType) = sc.getStageInputBytes(lineage, rootLineage)
            logInfoSSP(s"stage ${stage.id} input size = $stageInputBytes bytes from $startType", isSSparkLogEnabled)

            if (sc.optimizeMode == "ahead") {
              val startSC = System.currentTimeMillis()
              sc.selectCandidates(stage.id, stageInputBytes) 
              sc.checkOptimizeCache(stage.rdd)
              val endSC = System.currentTimeMillis()
              val timeSC = endSC - startSC
              logInfoSSP(s"[Ahead mode] selectCandidates time: ${timeSC} ms", isSSparkLogEnabled)
            }
            else if (sc.optimizeMode == "overlap") {
              val thread = sc.createSelectCandidatesThread(stage.id, stage.rdd, stageInputBytes)
              thread.start
              selectCandidatesThread = Some(thread)
            }
            else {
              logErrorSSP(s"""Wrong sspark.optimizeMode: ${sc.optimizeMode} need "overlap" or "ahead"""")
            }
          }
          
          /*
          //var selectCandidates: Option[Thread] = None
          if (sc.isSSparkOptimizeEnabled) {
            val startSC = System.currentTimeMillis() // Non-thread impl
            val rootLineage = sc.rootLineage.last
            val lineage = sc.getLineage(stage.id)
            val stageInputBytes = {
              if (lineage.get(rootLineage).get.deps.contains(-2)) 
                sc.inputBytes
              else if (lineage.get(rootLineage).get.deps.contains(-1))
                sc.shuffleBytes
              else if (lineage.get(rootLineage).get.deps.contains(-3)) 
                0L
              else {
                logErrorSSP(s"Cannot find stage input size ${lineage.get(rootLineage)}")
                0L
              }
            }
            if (isSSparkLogEnabled) {
              val startType = { 
                if (lineage.get(rootLineage).get.deps.contains(-2)) 
                  "HadoopRDD"
                else if  (lineage.get(rootLineage).get.deps.contains(-1))
                  "ShuffleRDD"
                else if (lineage.get(rootLineage).get.deps.contains(-3))
                  "ParallelCollectionRDD"
                else
                  "ERROR"
              }
              logInfoSSP(s"stage ${stage.id} input size = $stageInputBytes bytes from $startType")
            }
            //val thread = sc.createSelectCandidatesThread(stage.id, stageInputBytes)
            //thread.start
            sc.selectCandidates(stage.id, stageInputBytes)  // Non-thread impl
            sc.checkOptimizeCache(stage.rdd)
            val endSC = System.currentTimeMillis()    // Non-thread impl
            val timeSC = endSC - startSC              // Non-thread impl
            logInfoSSP(s"selectCandidates time: ${timeSC}ms", isSSparkLogEnabled) // Non-thread impl
          }
          */
        
          logInfo("Submitting " + stage + " (" + stage.rdd + "), which has no missing parents")
          submitMissingTasks(stage, jobId.get)
        } else {
          for (parent <- missing) {
            submitStage(parent)
          }
          waitingStages += stage
        }
      }
    } else {
      abortStage(stage, "No active job for stage " + stage.id, None)
    }
  }

  /** Called when stage's parents are available and we can now do its task. */
  private def submitMissingTasks(stage: Stage, jobId: Int) {
    logDebug("submitMissingTasks(" + stage + ")")

    // First figure out the indexes of partition ids to compute.
    val partitionsToCompute: Seq[Int] = stage.findMissingPartitions()

    // Use the scheduling pool, job group, description, etc. from an ActiveJob associated
    // with this Stage
    val properties = jobIdToActiveJob(jobId).properties

    runningStages += stage
    // SparkListenerStageSubmitted should be posted before testing whether tasks are
    // serializable. If tasks are not serializable, a SparkListenerStageCompleted event
    // will be posted, which should always come after a corresponding SparkListenerStageSubmitted
    // event.
    stage match {
      case s: ShuffleMapStage =>
        outputCommitCoordinator.stageStart(stage = s.id, maxPartitionId = s.numPartitions - 1)
      case s: ResultStage =>
        outputCommitCoordinator.stageStart(
          stage = s.id, maxPartitionId = s.rdd.partitions.length - 1)
    }
    val taskIdToLocations: Map[Int, Seq[TaskLocation]] = try {
      stage match {
        case s: ShuffleMapStage =>
          partitionsToCompute.map { id => (id, getPreferredLocs(stage.rdd, id))}.toMap
        case s: ResultStage =>
          partitionsToCompute.map { id =>
            val p = s.partitions(id)
            (id, getPreferredLocs(stage.rdd, p))
          }.toMap
      }
    } catch {
      case NonFatal(e) =>
        stage.makeNewStageAttempt(partitionsToCompute.size)
        listenerBus.post(SparkListenerStageSubmitted(stage.latestInfo, properties))
        abortStage(stage, s"Task creation failed: $e\n${Utils.exceptionString(e)}", Some(e))
        runningStages -= stage
        return
    }

    stage.makeNewStageAttempt(partitionsToCompute.size, taskIdToLocations.values.toSeq)

    // If there are tasks to execute, record the submission time of the stage. Otherwise,
    // post the even without the submission time, which indicates that this stage was
    // skipped.
    if (partitionsToCompute.nonEmpty) {
      stage.latestInfo.submissionTime = Some(clock.getTimeMillis())
    }
    listenerBus.post(SparkListenerStageSubmitted(stage.latestInfo, properties))

    // TODO: Maybe we can keep the taskBinary in Stage to avoid serializing it multiple times.
    // Broadcasted binary for the task, used to dispatch tasks to executors. Note that we broadcast
    // the serialized copy of the RDD and for each task we will deserialize it, which means each
    // task gets a different copy of the RDD. This provides stronger isolation between tasks that
    // might modify state of objects referenced in their closures. This is necessary in Hadoop
    // where the JobConf/Configuration object is not thread-safe.
    var taskBinary: Broadcast[Array[Byte]] = null
    var partitions: Array[Partition] = null
    try {
      // For ShuffleMapTask, serialize and broadcast (rdd, shuffleDep).
      // For ResultTask, serialize and broadcast (rdd, func).
      var taskBinaryBytes: Array[Byte] = null
      // taskBinaryBytes and partitions are both effected by the checkpoint status. We need
      // this synchronization in case another concurrent job is checkpointing this RDD, so we get a
      // consistent view of both variables.
      RDDCheckpointData.synchronized {
        taskBinaryBytes = stage match {
          case stage: ShuffleMapStage =>
            JavaUtils.bufferToArray(
              closureSerializer.serialize((stage.rdd, stage.shuffleDep): AnyRef))
          case stage: ResultStage =>
            JavaUtils.bufferToArray(closureSerializer.serialize((stage.rdd, stage.func): AnyRef))
        }

        partitions = stage.rdd.partitions
      }

      taskBinary = sc.broadcast(taskBinaryBytes)
    } catch {
      // In the case of a failure during serialization, abort the stage.
      case e: NotSerializableException =>
        abortStage(stage, "Task not serializable: " + e.toString, Some(e))
        runningStages -= stage

        // Abort execution
        return
      case e: Throwable =>
        abortStage(stage, s"Task serialization failed: $e\n${Utils.exceptionString(e)}", Some(e))
        runningStages -= stage

        // Abort execution
        return
    }

    val tasks: Seq[Task[_]] = try {
      val serializedTaskMetrics = closureSerializer.serialize(stage.latestInfo.taskMetrics).array()
      stage match {
        case stage: ShuffleMapStage =>
          stage.pendingPartitions.clear()
          partitionsToCompute.map { id =>
            val locs = taskIdToLocations(id)
            val part = partitions(id)
            stage.pendingPartitions += id
            new ShuffleMapTask(stage.id, stage.latestInfo.attemptNumber,
              taskBinary, part, locs, properties, serializedTaskMetrics, Option(jobId),
              Option(sc.applicationId), sc.applicationAttemptId, stage.rdd.isBarrier())
          }

        case stage: ResultStage =>
          partitionsToCompute.map { id =>
            val p: Int = stage.partitions(id)
            val part = partitions(p)
            val locs = taskIdToLocations(id)
            new ResultTask(stage.id, stage.latestInfo.attemptNumber,
              taskBinary, part, locs, id, properties, serializedTaskMetrics,
              Option(jobId), Option(sc.applicationId), sc.applicationAttemptId,
              stage.rdd.isBarrier())
          }
      }
    } catch {
      case NonFatal(e) =>
        abortStage(stage, s"Task creation failed: $e\n${Utils.exceptionString(e)}", Some(e))
        runningStages -= stage
        return
    }

    if (tasks.size > 0) {
      logInfo(s"Submitting ${tasks.size} missing tasks from $stage (${stage.rdd}) (first 15 " +
        s"tasks are for partitions ${tasks.take(15).map(_.partitionId)})")
      taskScheduler.submitTasks(new TaskSet(
        tasks.toArray, stage.id, stage.latestInfo.attemptNumber, jobId, properties))
    } else {
      // Because we posted SparkListenerStageSubmitted earlier, we should mark
      // the stage as completed here in case there are no tasks to run
      markStageAsFinished(stage, None)

      stage match {
        case stage: ShuffleMapStage =>
          logDebug(s"Stage ${stage} is actually done; " +
              s"(available: ${stage.isAvailable}," +
              s"available outputs: ${stage.numAvailableOutputs}," +
              s"partitions: ${stage.numPartitions})")
          markMapStageJobsAsFinished(stage)
        case stage : ResultStage =>
          logDebug(s"Stage ${stage} is actually done; (partitions: ${stage.numPartitions})")
      }
      submitWaitingChildStages(stage)
    }
  }

  /**
   * Merge local values from a task into the corresponding accumulators previously registered
   * here on the driver.
   *
   * Although accumulators themselves are not thread-safe, this method is called only from one
   * thread, the one that runs the scheduling loop. This means we only handle one task
   * completion event at a time so we don't need to worry about locking the accumulators.
   * This still doesn't stop the caller from updating the accumulator outside the scheduler,
   * but that's not our problem since there's nothing we can do about that.
   */
  private def updateAccumulators(event: CompletionEvent): Unit = {
    val task = event.task
    val stage = stageIdToStage(task.stageId)

    event.accumUpdates.foreach { updates =>
      val id = updates.id
      try {
        // Find the corresponding accumulator on the driver and update it
        val acc: AccumulatorV2[Any, Any] = AccumulatorContext.get(id) match {
          case Some(accum) => accum.asInstanceOf[AccumulatorV2[Any, Any]]
          case None =>
            throw new SparkException(s"attempted to access non-existent accumulator $id")
        }
        acc.merge(updates.asInstanceOf[AccumulatorV2[Any, Any]])
        // To avoid UI cruft, ignore cases where value wasn't updated
        if (acc.name.isDefined && !updates.isZero) {
          stage.latestInfo.accumulables(id) = acc.toInfo(None, Some(acc.value))
          event.taskInfo.setAccumulables(
            acc.toInfo(Some(updates.value), Some(acc.value)) +: event.taskInfo.accumulables)
        }
      } catch {
        case NonFatal(e) =>
          // Log the class name to make it easy to find the bad implementation
          val accumClassName = AccumulatorContext.get(id) match {
            case Some(accum) => accum.getClass.getName
            case None => "Unknown class"
          }
          logError(
            s"Failed to update accumulator $id ($accumClassName) for task ${task.partitionId}",
            e)
      }
    }
    // SSPARK: may have null exception, just for checking consistency
    //logInfoSSP(s"received BlockTime from task ${event.taskInfo.id} ${event.accumUpdates.find(a => a.name == Some(InternalAccumulator.BLOCK_TIME)).get.value}")
    //logInfoSSP(s"received BlockSize from task ${event.taskInfo.id} ${event.accumUpdates.find(a => a.name == Some(InternalAccumulator.BLOCK_SIZE)).get.value}")
  }

  private def postTaskEnd(event: CompletionEvent): Unit = {
    val taskMetrics: TaskMetrics =
      if (event.accumUpdates.nonEmpty) {
        try {
          TaskMetrics.fromAccumulators(event.accumUpdates)
        } catch {
          case NonFatal(e) =>
            val taskId = event.taskInfo.taskId
            logError(s"Error when attempting to reconstruct metrics for task $taskId", e)
            null
        }
      } else {
        null
      }

    listenerBus.post(SparkListenerTaskEnd(event.task.stageId, event.task.stageAttemptId,
      Utils.getFormattedClassName(event.task), event.reason, event.taskInfo, taskMetrics))
  }

  /**
   * Responds to a task finishing. This is called inside the event loop so it assumes that it can
   * modify the scheduler's internal state. Use taskEnded() to post a task end event from outside.
   */
  private[scheduler] def handleTaskCompletion(event: CompletionEvent) {
    val task = event.task
    val stageId = task.stageId

    outputCommitCoordinator.taskCompleted(
      stageId,
      task.stageAttemptId,
      task.partitionId,
      event.taskInfo.attemptNumber, // this is a task attempt number
      event.reason)

    if (!stageIdToStage.contains(task.stageId)) {
      // The stage may have already finished when we get this event -- eg. maybe it was a
      // speculative task. It is important that we send the TaskEnd event in any case, so listeners
      // are properly notified and can chose to handle it. For instance, some listeners are
      // doing their own accounting and if they don't get the task end event they think
      // tasks are still running when they really aren't.
      postTaskEnd(event)

      // Skip all the actions if the stage has been cancelled.
      return
    }

    val stage = stageIdToStage(task.stageId)

    // Make sure the task's accumulators are updated before any other processing happens, so that
    // we can post a task end event before any jobs or stages are updated. The accumulators are
    // only updated in certain cases.
    event.reason match {
      case Success =>
        task match {
          case rt: ResultTask[_, _] =>
            val resultStage = stage.asInstanceOf[ResultStage]
            resultStage.activeJob match {
              case Some(job) =>
                // Only update the accumulator once for each result task.
                if (!job.finished(rt.outputId)) {
                  updateAccumulators(event)
                }
              case None => // Ignore update if task's job has finished.
            }
          case _ =>
            updateAccumulators(event)
        }
      case _: ExceptionFailure | _: TaskKilled => updateAccumulators(event)
      case _ =>
    }
    postTaskEnd(event)

    event.reason match {
      case Success =>
        task match {
          case rt: ResultTask[_, _] =>
            // Cast to ResultStage here because it's part of the ResultTask
            // TODO Refactor this out to a function that accepts a ResultStage
            val resultStage = stage.asInstanceOf[ResultStage]
            resultStage.activeJob match {
              case Some(job) =>
                if (!job.finished(rt.outputId)) {
                  job.finished(rt.outputId) = true
                  job.numFinished += 1
                  // If the whole job has finished, remove it
                  if (job.numFinished == job.numPartitions) {
                    markStageAsFinished(resultStage)
                    cleanupStateForJobAndIndependentStages(job)
                    listenerBus.post(
                      SparkListenerJobEnd(job.jobId, clock.getTimeMillis(), JobSucceeded))
                  }

                  // taskSucceeded runs some user code that might throw an exception. Make sure
                  // we are resilient against that.
                  try {
                    job.listener.taskSucceeded(rt.outputId, event.result)
                  } catch {
                    case e: Exception =>
                      // TODO: Perhaps we want to mark the resultStage as failed?
                      job.listener.jobFailed(new SparkDriverExecutionException(e))
                  }
                }
              case None =>
                logInfo("Ignoring result from " + rt + " because its job has finished")
            }

          case smt: ShuffleMapTask =>
            val shuffleStage = stage.asInstanceOf[ShuffleMapStage]
            shuffleStage.pendingPartitions -= task.partitionId
            val status = event.result.asInstanceOf[MapStatus]
            val execId = status.location.executorId
            logDebug("ShuffleMapTask finished on " + execId)
            if (executorFailureEpoch.contains(execId) &&
                smt.epoch <= executorFailureEpoch(execId)) {
              logInfo(s"Ignoring possibly bogus $smt completion from executor $execId")
            } else {
              // The epoch of the task is acceptable (i.e., the task was launched after the most
              // recent failure we're aware of for the executor), so mark the task's output as
              // available.
              mapOutputTracker.registerMapOutput(
                shuffleStage.shuffleDep.shuffleId, smt.partitionId, status)
            }

            if (runningStages.contains(shuffleStage) && shuffleStage.pendingPartitions.isEmpty) {
              markStageAsFinished(shuffleStage)
              logInfo("looking for newly runnable stages")
              logInfo("running: " + runningStages)
              logInfo("waiting: " + waitingStages)
              logInfo("failed: " + failedStages)

              // This call to increment the epoch may not be strictly necessary, but it is retained
              // for now in order to minimize the changes in behavior from an earlier version of the
              // code. This existing behavior of always incrementing the epoch following any
              // successful shuffle map stage completion may have benefits by causing unneeded
              // cached map outputs to be cleaned up earlier on executors. In the future we can
              // consider removing this call, but this will require some extra investigation.
              // See https://github.com/apache/spark/pull/17955/files#r117385673 for more details.
              mapOutputTracker.incrementEpoch()

              clearCacheLocs()

              if (!shuffleStage.isAvailable) {
                // Some tasks had failed; let's resubmit this shuffleStage.
                // TODO: Lower-level scheduler should also deal with this
                logInfo("Resubmitting " + shuffleStage + " (" + shuffleStage.name +
                  ") because some of its tasks had failed: " +
                  shuffleStage.findMissingPartitions().mkString(", "))
                submitStage(shuffleStage)
              } else {
                markMapStageJobsAsFinished(shuffleStage)
                submitWaitingChildStages(shuffleStage)
              }
            }
        }

      case FetchFailed(bmAddress, shuffleId, mapId, _, failureMessage) =>
        val failedStage = stageIdToStage(task.stageId)
        val mapStage = shuffleIdToMapStage(shuffleId)

        if (failedStage.latestInfo.attemptNumber != task.stageAttemptId) {
          logInfo(s"Ignoring fetch failure from $task as it's from $failedStage attempt" +
            s" ${task.stageAttemptId} and there is a more recent attempt for that stage " +
            s"(attempt ${failedStage.latestInfo.attemptNumber}) running")
        } else {
          failedStage.failedAttemptIds.add(task.stageAttemptId)
          val shouldAbortStage =
            failedStage.failedAttemptIds.size >= maxConsecutiveStageAttempts ||
            disallowStageRetryForTest

          // It is likely that we receive multiple FetchFailed for a single stage (because we have
          // multiple tasks running concurrently on different executors). In that case, it is
          // possible the fetch failure has already been handled by the scheduler.
          if (runningStages.contains(failedStage)) {
            logInfo(s"Marking $failedStage (${failedStage.name}) as failed " +
              s"due to a fetch failure from $mapStage (${mapStage.name})")
            markStageAsFinished(failedStage, errorMessage = Some(failureMessage),
              willRetry = !shouldAbortStage)
          } else {
            logDebug(s"Received fetch failure from $task, but it's from $failedStage which is no " +
              "longer running")
          }

          if (mapStage.rdd.isBarrier()) {
            // Mark all the map as broken in the map stage, to ensure retry all the tasks on
            // resubmitted stage attempt.
            mapOutputTracker.unregisterAllMapOutput(shuffleId)
          } else if (mapId != -1) {
            // Mark the map whose fetch failed as broken in the map stage
            mapOutputTracker.unregisterMapOutput(shuffleId, mapId, bmAddress)
          }

          if (failedStage.rdd.isBarrier()) {
            failedStage match {
              case failedMapStage: ShuffleMapStage =>
                // Mark all the map as broken in the map stage, to ensure retry all the tasks on
                // resubmitted stage attempt.
                mapOutputTracker.unregisterAllMapOutput(failedMapStage.shuffleDep.shuffleId)

              case failedResultStage: ResultStage =>
                // Abort the failed result stage since we may have committed output for some
                // partitions.
                val reason = "Could not recover from a failed barrier ResultStage. Most recent " +
                  s"failure reason: $failureMessage"
                abortStage(failedResultStage, reason, None)
            }
          }

          if (shouldAbortStage) {
            val abortMessage = if (disallowStageRetryForTest) {
              "Fetch failure will not retry stage due to testing config"
            } else {
              s"""$failedStage (${failedStage.name})
                 |has failed the maximum allowable number of
                 |times: $maxConsecutiveStageAttempts.
                 |Most recent failure reason: $failureMessage""".stripMargin.replaceAll("\n", " ")
            }
            abortStage(failedStage, abortMessage, None)
          } else { // update failedStages and make sure a ResubmitFailedStages event is enqueued
            // TODO: Cancel running tasks in the failed stage -- cf. SPARK-17064
            val noResubmitEnqueued = !failedStages.contains(failedStage)
            failedStages += failedStage
            failedStages += mapStage
            if (noResubmitEnqueued) {
              // If the map stage is INDETERMINATE, which means the map tasks may return
              // different result when re-try, we need to re-try all the tasks of the failed
              // stage and its succeeding stages, because the input data will be changed after the
              // map tasks are re-tried.
              // Note that, if map stage is UNORDERED, we are fine. The shuffle partitioner is
              // guaranteed to be determinate, so the input data of the reducers will not change
              // even if the map tasks are re-tried.
              if (mapStage.rdd.outputDeterministicLevel == DeterministicLevel.INDETERMINATE) {
                // It's a little tricky to find all the succeeding stages of `mapStage`, because
                // each stage only know its parents not children. Here we traverse the stages from
                // the leaf nodes (the result stages of active jobs), and rollback all the stages
                // in the stage chains that connect to the `mapStage`. To speed up the stage
                // traversing, we collect the stages to rollback first. If a stage needs to
                // rollback, all its succeeding stages need to rollback to.
                val stagesToRollback = HashSet[Stage](mapStage)

                def collectStagesToRollback(stageChain: List[Stage]): Unit = {
                  if (stagesToRollback.contains(stageChain.head)) {
                    stageChain.drop(1).foreach(s => stagesToRollback += s)
                  } else {
                    stageChain.head.parents.foreach { s =>
                      collectStagesToRollback(s :: stageChain)
                    }
                  }
                }

                def generateErrorMessage(stage: Stage): String = {
                  "A shuffle map stage with indeterminate output was failed and retried. " +
                    s"However, Spark cannot rollback the $stage to re-process the input data, " +
                    "and has to fail this job. Please eliminate the indeterminacy by " +
                    "checkpointing the RDD before repartition and try again."
                }

                activeJobs.foreach(job => collectStagesToRollback(job.finalStage :: Nil))

                stagesToRollback.foreach {
                  case mapStage: ShuffleMapStage =>
                    val numMissingPartitions = mapStage.findMissingPartitions().length
                    if (numMissingPartitions < mapStage.numTasks) {
                      // TODO: support to rollback shuffle files.
                      // Currently the shuffle writing is "first write wins", so we can't re-run a
                      // shuffle map stage and overwrite existing shuffle files. We have to finish
                      // SPARK-8029 first.
                      abortStage(mapStage, generateErrorMessage(mapStage), None)
                    }

                  case resultStage: ResultStage if resultStage.activeJob.isDefined =>
                    val numMissingPartitions = resultStage.findMissingPartitions().length
                    if (numMissingPartitions < resultStage.numTasks) {
                      // TODO: support to rollback result tasks.
                      abortStage(resultStage, generateErrorMessage(resultStage), None)
                    }

                  case _ =>
                }
              }

              // We expect one executor failure to trigger many FetchFailures in rapid succession,
              // but all of those task failures can typically be handled by a single resubmission of
              // the failed stage.  We avoid flooding the scheduler's event queue with resubmit
              // messages by checking whether a resubmit is already in the event queue for the
              // failed stage.  If there is already a resubmit enqueued for a different failed
              // stage, that event would also be sufficient to handle the current failed stage, but
              // producing a resubmit for each failed stage makes debugging and logging a little
              // simpler while not producing an overwhelming number of scheduler events.
              logInfo(
                s"Resubmitting $mapStage (${mapStage.name}) and " +
                  s"$failedStage (${failedStage.name}) due to fetch failure"
              )
              messageScheduler.schedule(
                new Runnable {
                  override def run(): Unit = eventProcessLoop.post(ResubmitFailedStages)
                },
                DAGScheduler.RESUBMIT_TIMEOUT,
                TimeUnit.MILLISECONDS
              )
            }
          }

          // TODO: mark the executor as failed only if there were lots of fetch failures on it
          if (bmAddress != null) {
            val hostToUnregisterOutputs = if (env.blockManager.externalShuffleServiceEnabled &&
              unRegisterOutputOnHostOnFetchFailure) {
              // We had a fetch failure with the external shuffle service, so we
              // assume all shuffle data on the node is bad.
              Some(bmAddress.host)
            } else {
              // Unregister shuffle data just for one executor (we don't have any
              // reason to believe shuffle data has been lost for the entire host).
              None
            }
            removeExecutorAndUnregisterOutputs(
              execId = bmAddress.executorId,
              fileLost = true,
              hostToUnregisterOutputs = hostToUnregisterOutputs,
              maybeEpoch = Some(task.epoch))
          }
        }

      case failure: TaskFailedReason if task.isBarrier =>
        // Also handle the task failed reasons here.
        failure match {
          case Resubmitted =>
            handleResubmittedFailure(task, stage)

          case _ => // Do nothing.
        }

        // Always fail the current stage and retry all the tasks when a barrier task fail.
        val failedStage = stageIdToStage(task.stageId)
        if (failedStage.latestInfo.attemptNumber != task.stageAttemptId) {
          logInfo(s"Ignoring task failure from $task as it's from $failedStage attempt" +
            s" ${task.stageAttemptId} and there is a more recent attempt for that stage " +
            s"(attempt ${failedStage.latestInfo.attemptNumber}) running")
        } else {
          logInfo(s"Marking $failedStage (${failedStage.name}) as failed due to a barrier task " +
            "failed.")
          val message = s"Stage failed because barrier task $task finished unsuccessfully.\n" +
            failure.toErrorString
          try {
            // killAllTaskAttempts will fail if a SchedulerBackend does not implement killTask.
            val reason = s"Task $task from barrier stage $failedStage (${failedStage.name}) " +
              "failed."
            taskScheduler.killAllTaskAttempts(stageId, interruptThread = false, reason)
          } catch {
            case e: UnsupportedOperationException =>
              // Cannot continue with barrier stage if failed to cancel zombie barrier tasks.
              // TODO SPARK-24877 leave the zombie tasks and ignore their completion events.
              logWarning(s"Could not kill all tasks for stage $stageId", e)
              abortStage(failedStage, "Could not kill zombie barrier tasks for stage " +
                s"$failedStage (${failedStage.name})", Some(e))
          }
          markStageAsFinished(failedStage, Some(message))

          failedStage.failedAttemptIds.add(task.stageAttemptId)
          // TODO Refactor the failure handling logic to combine similar code with that of
          // FetchFailed.
          val shouldAbortStage =
            failedStage.failedAttemptIds.size >= maxConsecutiveStageAttempts ||
              disallowStageRetryForTest

          if (shouldAbortStage) {
            val abortMessage = if (disallowStageRetryForTest) {
              "Barrier stage will not retry stage due to testing config. Most recent failure " +
                s"reason: $message"
            } else {
              s"""$failedStage (${failedStage.name})
                 |has failed the maximum allowable number of
                 |times: $maxConsecutiveStageAttempts.
                 |Most recent failure reason: $message
               """.stripMargin.replaceAll("\n", " ")
            }
            abortStage(failedStage, abortMessage, None)
          } else {
            failedStage match {
              case failedMapStage: ShuffleMapStage =>
                // Mark all the map as broken in the map stage, to ensure retry all the tasks on
                // resubmitted stage attempt.
                mapOutputTracker.unregisterAllMapOutput(failedMapStage.shuffleDep.shuffleId)

              case failedResultStage: ResultStage =>
                // Abort the failed result stage since we may have committed output for some
                // partitions.
                val reason = "Could not recover from a failed barrier ResultStage. Most recent " +
                  s"failure reason: $message"
                abortStage(failedResultStage, reason, None)
            }
            // In case multiple task failures triggered for a single stage attempt, ensure we only
            // resubmit the failed stage once.
            val noResubmitEnqueued = !failedStages.contains(failedStage)
            failedStages += failedStage
            if (noResubmitEnqueued) {
              logInfo(s"Resubmitting $failedStage (${failedStage.name}) due to barrier stage " +
                "failure.")
              messageScheduler.schedule(new Runnable {
                override def run(): Unit = eventProcessLoop.post(ResubmitFailedStages)
              }, DAGScheduler.RESUBMIT_TIMEOUT, TimeUnit.MILLISECONDS)
            }
          }
        }

      case Resubmitted =>
        handleResubmittedFailure(task, stage)

      case _: TaskCommitDenied =>
        // Do nothing here, left up to the TaskScheduler to decide how to handle denied commits

      case _: ExceptionFailure | _: TaskKilled =>
        // Nothing left to do, already handled above for accumulator updates.

      case TaskResultLost =>
        // Do nothing here; the TaskScheduler handles these failures and resubmits the task.

      case _: ExecutorLostFailure | UnknownReason =>
        // Unrecognized failure - also do nothing. If the task fails repeatedly, the TaskScheduler
        // will abort the job.
    }
  }

  private def handleResubmittedFailure(task: Task[_], stage: Stage): Unit = {
    logInfo(s"Resubmitted $task, so marking it as still running.")
    stage match {
      case sms: ShuffleMapStage =>
        sms.pendingPartitions += task.partitionId

      case _ =>
        throw new SparkException("TaskSetManagers should only send Resubmitted task " +
          "statuses for tasks in ShuffleMapStages.")
    }
  }

  private[scheduler] def markMapStageJobsAsFinished(shuffleStage: ShuffleMapStage): Unit = {
    // Mark any map-stage jobs waiting on this stage as finished
    if (shuffleStage.isAvailable && shuffleStage.mapStageJobs.nonEmpty) {
      val stats = mapOutputTracker.getStatistics(shuffleStage.shuffleDep)
      for (job <- shuffleStage.mapStageJobs) {
        markMapStageJobAsFinished(job, stats)
      }
    }
  }

  /**
   * Responds to an executor being lost. This is called inside the event loop, so it assumes it can
   * modify the scheduler's internal state. Use executorLost() to post a loss event from outside.
   *
   * We will also assume that we've lost all shuffle blocks associated with the executor if the
   * executor serves its own blocks (i.e., we're not using an external shuffle service), or the
   * entire Standalone worker is lost.
   */
  private[scheduler] def handleExecutorLost(
      execId: String,
      workerLost: Boolean): Unit = {
    // if the cluster manager explicitly tells us that the entire worker was lost, then
    // we know to unregister shuffle output.  (Note that "worker" specifically refers to the process
    // from a Standalone cluster, where the shuffle service lives in the Worker.)
    val fileLost = workerLost || !env.blockManager.externalShuffleServiceEnabled
    removeExecutorAndUnregisterOutputs(
      execId = execId,
      fileLost = fileLost,
      hostToUnregisterOutputs = None,
      maybeEpoch = None)
  }

  /**
   * Handles removing an executor from the BlockManagerMaster as well as unregistering shuffle
   * outputs for the executor or optionally its host.
   *
   * @param execId executor to be removed
   * @param fileLost If true, indicates that we assume we've lost all shuffle blocks associated
   *   with the executor; this happens if the executor serves its own blocks (i.e., we're not
   *   using an external shuffle service), the entire Standalone worker is lost, or a FetchFailed
   *   occurred (in which case we presume all shuffle data related to this executor to be lost).
   * @param hostToUnregisterOutputs (optional) executor host if we're unregistering all the
   *   outputs on the host
   * @param maybeEpoch (optional) the epoch during which the failure was caught (this prevents
   *   reprocessing for follow-on fetch failures)
   */
  private def removeExecutorAndUnregisterOutputs(
      execId: String,
      fileLost: Boolean,
      hostToUnregisterOutputs: Option[String],
      maybeEpoch: Option[Long] = None): Unit = {
    val currentEpoch = maybeEpoch.getOrElse(mapOutputTracker.getEpoch)
    logDebug(s"Considering removal of executor $execId; " +
      s"fileLost: $fileLost, currentEpoch: $currentEpoch")
    if (!executorFailureEpoch.contains(execId) || executorFailureEpoch(execId) < currentEpoch) {
      executorFailureEpoch(execId) = currentEpoch
      logInfo(s"Executor lost: $execId (epoch $currentEpoch)")
      blockManagerMaster.removeExecutor(execId)
      clearCacheLocs()
    }
    if (fileLost &&
        (!shuffleFileLostEpoch.contains(execId) || shuffleFileLostEpoch(execId) < currentEpoch)) {
      shuffleFileLostEpoch(execId) = currentEpoch
      hostToUnregisterOutputs match {
        case Some(host) =>
          logInfo(s"Shuffle files lost for host: $host (epoch $currentEpoch)")
          mapOutputTracker.removeOutputsOnHost(host)
        case None =>
          logInfo(s"Shuffle files lost for executor: $execId (epoch $currentEpoch)")
          mapOutputTracker.removeOutputsOnExecutor(execId)
      }
    }
  }

  /**
   * Responds to a worker being removed. This is called inside the event loop, so it assumes it can
   * modify the scheduler's internal state. Use workerRemoved() to post a loss event from outside.
   *
   * We will assume that we've lost all shuffle blocks associated with the host if a worker is
   * removed, so we will remove them all from MapStatus.
   *
   * @param workerId identifier of the worker that is removed.
   * @param host host of the worker that is removed.
   * @param message the reason why the worker is removed.
   */
  private[scheduler] def handleWorkerRemoved(
      workerId: String,
      host: String,
      message: String): Unit = {
    logInfo("Shuffle files lost for worker %s on host %s".format(workerId, host))
    mapOutputTracker.removeOutputsOnHost(host)
    clearCacheLocs()
  }

  private[scheduler] def handleExecutorAdded(execId: String, host: String) {
    // remove from executorFailureEpoch(execId) ?
    if (executorFailureEpoch.contains(execId)) {
      logInfo("Host added was in lost list earlier: " + host)
      executorFailureEpoch -= execId
    }
    shuffleFileLostEpoch -= execId
  }

  private[scheduler] def handleStageCancellation(stageId: Int, reason: Option[String]) {
    stageIdToStage.get(stageId) match {
      case Some(stage) =>
        val jobsThatUseStage: Array[Int] = stage.jobIds.toArray
        jobsThatUseStage.foreach { jobId =>
          val reasonStr = reason match {
            case Some(originalReason) =>
              s"because $originalReason"
            case None =>
              s"because Stage $stageId was cancelled"
          }
          handleJobCancellation(jobId, Option(reasonStr))
        }
      case None =>
        logInfo("No active jobs to kill for Stage " + stageId)
    }
  }

  private[scheduler] def handleJobCancellation(jobId: Int, reason: Option[String]) {
    if (!jobIdToStageIds.contains(jobId)) {
      logDebug("Trying to cancel unregistered job " + jobId)
    } else {
      failJobAndIndependentStages(
        jobIdToActiveJob(jobId), "Job %d cancelled %s".format(jobId, reason.getOrElse("")))
    }
  }
  
  private def isHadoopRDD(op: String): Boolean = {
    if (op == "hadoopFile" ||
      op == "objectFile" ||
      op == "sequenceFile" ||
      op == "textFile") {
      true
    }
    else
      false
  }

  /* 
   *  SSPARK: A function that calculates the RDD size by merging the sizes of each block based on the RDD id.
   */
  private def blockSizeToRddSize(blockSize: java.util.List[(Int, Long)], stageId: Int): HashMap[Int, Long] ={
    val lineage = sc.getLineage(stageId)
    var rddSize = new HashMap[Int, Long]
    for(i <- 0 to blockSize.size()-1) {
      val cur = blockSize.get(i)
      if (rddSize.contains(cur._1)) {
        rddSize(cur._1) += cur._2
      }
      else {
        if (lineage.contains(cur._1))
          rddSize.put(cur._1, cur._2)
        else
          logErrorSSP(s"unrecognized BlockSize for rdd id ${cur._1} on stage ${stageId}")
      }
    }
    rddSize
  }
  
  /* 
   *  SSPARK: A function that calculates the RDD time by merging the times of each block based on the RDD id.
   *  This calculates the RDD computation time using a heuristic method. 
   *  The absolute execution time of RDD cannot be obtained in Spark, a distributed environment.
   *  We assume that the RDD time is the ratio of the calculation time of RDD blocks(tasks) 
   *  to the total stage time using the following formula.
   *  RDD time = sum times of corresponding blocks / sum times of all block times in the stage * time of stage
   */
  private def blockTimeToRddTime(blockTime: java.util.List[(Int, Long)], stageTime: Long, stageId: Int): HashMap[Int, Long] ={
    val lineage = sc.getLineage(stageId)
    var rddTime = new HashMap[Int, Long]
    var sumBlockTime = 0L
    for(i <- 0 to blockTime.size()-1) {
      val cur = blockTime.get(i)
      //if(cur._1 == 0){ // only BlockTime has rdd 0, and it should be accumulated into rdd 1
      if(cur._1 == 0 && isHadoopRDD(lineage(cur._1).name)) {
        if (rddTime.contains(1)) {
          rddTime(1) += cur._2
          sumBlockTime += cur._2
        }
        else {
          rddTime.put(1, cur._2)
          sumBlockTime += cur._2
        }
      }
      else {
        if (rddTime.contains(cur._1)) {
          rddTime(cur._1) += cur._2
          sumBlockTime += cur._2
        }
        else {
          if (lineage.contains(cur._1)) {
            rddTime.put(cur._1, cur._2)
            sumBlockTime += cur._2
          }
          else
            logErrorSSP(s"unrecognized BlockTime for rdd id ${cur._1} on stage ${stageId}")
        }
      }
    }
    rddTime = rddTime.map ( x => x._1 -> (x._2.toDouble / sumBlockTime.toDouble * stageTime.toDouble).toLong)
    rddTime
  }

  /* 
  *  SSPARK: A function that calculates and merges the profiled data
  */
  private def handleProfiled(stage: Stage): Unit = {
    val accum = stage.latestInfo.accumulables
    // may occur runtime error when submissionTime or completionTime is not set
    var rddTime = new HashMap[Int, Long]
    var rddSize = new HashMap[Int, Long]

    val stageNanoTime = (stage.latestInfo.completionTime.get - stage.latestInfo.submissionTime.get) * 1000000L
    
    val accumBlockTime = accum.find(x => x._2.name == Some(InternalAccumulator.BLOCK_TIME))
    accumBlockTime match{
      case Some(x) =>
        if (x._2.value != None) {
          val getBlockTime = x._2.value.get.asInstanceOf[java.util.List[(Int, Long)]]
          logInfoSSP(s"reduced BlockTime from stage ${stage.id} $getBlockTime", isSSparkLogEnabled)
          rddTime = blockTimeToRddTime(getBlockTime, stageNanoTime, stage.id)
          logInfoSSP(s"RddTime ${rddTime}", isSSparkLogEnabled)
        }
        else
          logErrorSSP("Couldn't find accumBlockTime value")  
      case _ => logInfoSSP("None accumBlockTime $accumBlockTime", isSSparkLogEnabled)  
    }

    val accumBlockSize = accum.find(x => x._2.name == Some(InternalAccumulator.BLOCK_SIZE))
    accumBlockSize match{
      case Some(x) =>
        if (x._2.value != None) {
          val getBlockSize = x._2.value.get.asInstanceOf[java.util.List[(Int, Long)]]
          logInfoSSP(s"reduced BlockSize from stage ${stage.id} $getBlockSize", isSSparkLogEnabled)
          rddSize = blockSizeToRddSize(getBlockSize, stage.id)
          logInfoSSP(s"RddSize ${rddSize}", isSSparkLogEnabled)
        }
        else
          logErrorSSP("Couldn't find accumBlockSize value")
      case _ => logInfoSSP("None accumBlockSize $accumBlockSize", isSSparkLogEnabled)  
    }

    /*
    // is it possible None? ALS Error
    val accumBlockTime = accum.find(x => x._2.name == Some(InternalAccumulator.BLOCK_TIME)).get._2
    if (accumBlockTime.value != None) {
      val getBlockTime = accumBlockTime.value.get.asInstanceOf[java.util.List[(Int, Long)]]
      logInfoSSP(s"reduced BlockTime from stage ${stage.id} $getBlockTime")
      rddTime = blockTimeToRddTime(getBlockTime, stageNanoTime)
      logInfoSSP(s"RddTime ${rddTime}")
    }
    else
      logErrorSSP("Couldn't find accumBlockTime value")  
    
    val accumBlockSize = accum.find(x => x._2.name == Some(InternalAccumulator.BLOCK_SIZE)).get._2
    if (accumBlockSize.value != None) {
      val getBlockSize = accumBlockSize.value.get.asInstanceOf[java.util.List[(Int, Long)]]
      logInfoSSP(s"reduced BlockSize from stage ${stage.id} $getBlockSize")
      rddSize = blockSizeToRddSize(getBlockSize)
      logInfoSSP(s"RddSize ${rddSize}")
    }
    else
      logErrorSSP("Couldn't find accumBlockSize value")
    */

    logInfoSSP(s"Lineage ${stage.id} ${sc.getLineage(stage.id)}", isSSparkLogEnabled) //
    //logInfoSSP(s"Lineages ${sc.lineages}")
    if (rddTime.size > 0 || rddSize.size > 0)
      makeSaveForm(stage, rddTime, rddSize)
    else
      logErrorSSP(s"Nothing in rddTime:${rddTime} or rddSize:${rddSize}")
    
  }

  val opTimePerIn = HashSet[(String, Long, Long)]()
  val opOutPerIn = HashSet[(String, Long, Long)]()

  // SSPARK: make "time per input size" & "output size per input size"
  private def makeSaveForm(stage: Stage, rddTime: HashMap[Int, Long], rddSize: HashMap[Int, Long]): Unit = {
    /* 
      1. need file size (read size) for RDD 1
          calc input size
      2. merge same rdd name & callSite into one operation
      3. filter unnecessary operations, e.g., shuffle 
          may start from lineage.head and when meet recoginized operation (sc)
    */
    
    val lineage = sc.getLineage(stage.id)
    val accum = stage.latestInfo.accumulables
    opTimePerIn.clear()
    opOutPerIn.clear()

    val accumInputBytes = accum.find(x => x._2.name == Some(InternalAccumulator.input.BYTES_READ)) match {
      case Some(x) => x._2.value.get.asInstanceOf[Long]
      case _ => 0L
    }
    val accumShuffleRemote = accum.find(x => x._2.name == Some(InternalAccumulator.shuffleRead.REMOTE_BYTES_READ)) match {
      case Some(x) => x._2.value.get.asInstanceOf[Long]
      case _ => 0L
    }
    val accumShuffleLocal = accum.find(x => x._2.name == Some(InternalAccumulator.shuffleRead.LOCAL_BYTES_READ)) match {
      case Some(x) => x._2.value.get.asInstanceOf[Long]
      case _ => 0L
    }
    val accumShuffleWrite = accum.find(x => x._2.name == Some(InternalAccumulator.shuffleWrite.BYTES_WRITTEN)) match {
      case Some(x) => x._2.value.get.asInstanceOf[Long]
      case _ => 0L
    }
    if (accumShuffleWrite != 0L)
      sc.shuffleBytes = accumShuffleWrite // assign current stage's write size for using next stage input

    val accumShuffleBytes: Long = accumShuffleRemote + accumShuffleLocal
    //is it possible that lineage has multiple rdd operations which are same callSite&name without dependency?
    //probably not... then don't need to check dependency, just check name & callSite
    
    def rddProfileToOpProfile(nameWithCallSite: String, in: Any, out: Any, time: Any): Unit = {
      val name = nameWithCallSite.split('(')(0)
      //if (sc.isShuffleOp(name)) {
        // do nothing, 
        // just comment out this condition, if all operation profiling needs
      //  None
      //}
      //else {
        if (out != None && in != None && time != None){
          if (opTimePerIn.find(x => x._1 == nameWithCallSite) != None
            && opOutPerIn.find(x => x._1 == nameWithCallSite) != None) {
            // exist already
            val prevElemTPI = opTimePerIn.find(x => x._1 == nameWithCallSite).get
            val prevElemOPI = opOutPerIn.find(x => x._1 == nameWithCallSite).get

            var updatedIn = prevElemOPI._2
            var updatedTime = prevElemTPI._3
            var updatedOut = prevElemOPI._3

            updatedIn = in.asInstanceOf[Long]       // change child input to parent input
            //updatedOut don't need to change, becasue it should be youngest node
            updatedTime += time.asInstanceOf[Long]  // RDD times should be added into one operation time

            opTimePerIn -= prevElemTPI
            opOutPerIn -= prevElemOPI
            opTimePerIn.add( (nameWithCallSite, updatedIn, updatedTime) )
            opOutPerIn.add( (nameWithCallSite, updatedIn, updatedOut) )

          }
          else if (opTimePerIn.find(x => x._1 == nameWithCallSite) != None
            || opOutPerIn.find(x => x._1 == nameWithCallSite) != None)
            logErrorSSP("Error case")
          else {
            opTimePerIn.add( (nameWithCallSite, in.asInstanceOf[Long], time.asInstanceOf[Long]) )
            opOutPerIn.add( (nameWithCallSite, in.asInstanceOf[Long], out.asInstanceOf[Long]) )
          }
        }
      //}
    }
    
    /*
    def multipleParents(x: SparkContext#LineageInfo): Boolean = {
      x.parent.size match {
        case 1 => false
        case 0 =>
          //println("No parents")
          false
        case _ => true
      }
    }*/

    val visited = HashSet[Int]()

    def lineageIterator(x: (Int, SparkContext#LineageInfo)): Unit = {
      val name = s"${x._2.name}(${x._2.callSite})"
      val out = rddSize.get(x._1).getOrElse(None)
      val time = rddTime.get(x._1).getOrElse(None)
     
      //if (!multipleParents(x._2)) {
      if (x._2.deps.size == 1) {
        val pId = x._2.deps.last
        
        // -1 is from stage, -2 is from job, 0 is from read file
        if (pId == -1 || pId == -2 || //pId == 0){
              (pId == 0 && isHadoopRDD(x._2.name)) ) 
              { // in this case, input size should be shuffle or inputBytes
          val in = if (accumInputBytes != 0L) accumInputBytes
          else if (accumShuffleBytes != 0L) accumShuffleBytes
          else {
            logErrorSSP(s"both of InputBytes and ShuffleBytes are zero, rdd id ${x._1} on stage ${stage.id}")
            None
          }
          logInfoSSP(s"visited ${x._1} name:$name input:$in output:$out time:$time", isSSparkLogEnabled)
          visited.add(x._1)
          rddProfileToOpProfile(name, in, out, time)
        }
        
        else {
          val in = rddSize.get(pId).getOrElse(None)
          logInfoSSP(s"visited ${x._1} name:$name input:$in output:$out time:$time", isSSparkLogEnabled)
          visited.add(x._1)
          rddProfileToOpProfile(name, in, out, time)
          val parent = lineage.find(k => k._1 == pId)
          if (parent != None) {
            if(!visited.contains(pId))
              lineageIterator(parent.get)
          }
          else 
            logInfoSSP(s"got parent None id $pId", isSSparkLogEnabled)
        }
      }

      else if (x._2.deps.size == 0) {  // ParallelCollectionRDD case
          val in = 0L
          logInfoSSP(s"visited ${x._1} name:$name input:$in output:$out time:$time", isSSparkLogEnabled)
          visited.add(x._1)
          rddProfileToOpProfile(name, in, out, time)
      }

      else {  // multipleParents
        val pIds = x._2.deps
        var in = {    // sum of parents RDD size, make sure to make it sense
          var sum = 0L
          pIds.map{x => 
            val size = rddSize.get(x).getOrElse(0L)
            sum += size
          }
          sum
        } // if there is shuffle dependency to previous stage, then one of size will be 0
        
        if (pIds.contains(-1)) {  // therefore, just add shuffle read bytes into in
          in += accumShuffleBytes
        }

        var tmpStr = "parents size: "
        pIds.map{x => 
          val size = rddSize.get(x).getOrElse(0L)
          tmpStr += s"[$x] $size "
        }

        logInfoSSP(s"$tmpStr, shuffleBytes: $accumShuffleBytes, in: $in", isSSparkLogEnabled)

        logInfoSSP(s"visited ${x._1} name:$name input:$in output:$out time:$time", isSSparkLogEnabled)
        visited.add(x._1)
        rddProfileToOpProfile(name, in, out, time)
        pIds.map(xi => lineage.find(k => k._1 == xi)) 
            .map { parent => 
              if (parent != None) {
                if (!visited.contains(parent.get._1))
                  lineageIterator(parent.get)
              }
              else
                logInfoSSP(s"got parent None", isSSparkLogEnabled)
            }
              
      }
    }

    /*
    lineage.foreach{ x =>
      val name = x._2.name
      var out: Long = -1L
      var in: Long = -1L
      var time: Long = -1L
      val multipleParents: Boolean = x._2.parent.size match {
        case 1 => false
        case 0 => 
          logErrorSSP("No parents")
          false
        case _ => true
      }

      if (rddSize.contains(x._1) && rddTime.contains(x._1)) {
        out = rddSize.get(x._1).get
        time = rddTime.get(x._1).get
        in = if (multipleParents) {
          var sum = 0L
          val pIds = x._2.parent
          pIds.map(x => sum += x)
          sum
        }
        else {
          val pId = x._2.parent.last
          rddTime.get(pId).get
        }
      }

      logInfoSSP(s"name $name, in $in, out $out, time $time")     
      
    }*/

    lineageIterator(lineage.head)
    logInfoSSP(s"stage ${stage.id} Input: ${accumInputBytes} Shuffle-Read: ${accumShuffleBytes}", isSSparkLogEnabled)
    logInfoSSP(s"OP output/input stage ${stage.id} = ${opOutPerIn}", isSSparkLogEnabled)
    logInfoSSP(s"OP time/input stage ${stage.id} = ${opTimePerIn}", isSSparkLogEnabled)
    
    def getFileWriter(opNameWithCallSite: String, timeOrSize: String): FileWriter = {
      val filePath = Paths.get(sc.profilingDir
        + "/" +opNameWithCallSite.split('(')(0)
        + s"_$timeOrSize.csv")
      if (Files.exists(filePath))
        new FileWriter(filePath.toString, true)
      else
        new FileWriter(new File(filePath.toString))
    }

    opOutPerIn.foreach{x =>
      val fw = getFileWriter(x._1, "size")
      val inMB = x._2.toFloat / Math.pow(2,20)
      val outMB = x._3.toFloat / Math.pow(2,20)
      fw.write(s"${x._1}, ${inMB}, ${outMB}\n") 
      fw.close()
    }
    //sc.opOutPerInAccum = opOutPerIn
    opTimePerIn.foreach{x =>
      val fw = getFileWriter(x._1, "time")
      val inMB = x._2.toFloat / Math.pow(2,20)
      val timeS = x._3.toFloat / 1e9
      fw.write(s"${x._1}, ${inMB}, ${timeS}\n") 
      fw.close()
    }
    //sc.opTimePerInAccum = opTimePerIn
    //doesn't need to 
  }

  /**
   * Marks a stage as finished and removes it from the list of running stages.
   */
  private def markStageAsFinished(
      stage: Stage,
      errorMessage: Option[String] = None,
      willRetry: Boolean = false): Unit = {
    val serviceTime = stage.latestInfo.submissionTime match {
      case Some(t) => "%.03f".format((clock.getTimeMillis() - t) / 1000.0)
      case _ => "Unknown"
    }

    if (selectCandidatesThread.isDefined) {
      val startJoin = System.currentTimeMillis()
      selectCandidatesThread.foreach(_.join())
      val endJoin = System.currentTimeMillis()
      val tTime = endJoin - startJoin
      logInfoSSP(s"[Overlap mode] selectCandidates thread overhead: ${tTime} ms", isSSparkLogEnabled)
    
      if (tTime > 0) {  //if (!isSSparkLogEnabled && tTime > 0){
        /* ALS application has huge amount of logs, so it adopts WARN log level
           for checking overhead of selectCandidates in all cases, change it into WARN level
        */
        logWarningSSP(s"selectCandidates thread time may have overhead: ${tTime} ms")
      }
    }

    if (errorMessage.isEmpty) {
      logInfo("%s (%s) finished in %s s".format(stage, stage.name, serviceTime))
      stage.latestInfo.completionTime = Some(clock.getTimeMillis())
      
      if (isSSparkProfileEnabled){
        val lineage = sc.getLineage(stage.id)
        if (lineage.size != 0)
          handleProfiled(stage)
        else
          logInfoSSP("is this real submitted stage?", isSSparkLogEnabled)
      }

      // Clear failure count for this stage, now that it's succeeded.
      // We only limit consecutive failures of stage attempts,so that if a stage is
      // re-used many times in a long-running job, unrelated failures don't eventually cause the
      // stage to be aborted.
      stage.clearFailures()
    } else {
      stage.latestInfo.stageFailed(errorMessage.get)
      logInfo(s"$stage (${stage.name}) failed in $serviceTime s due to ${errorMessage.get}")
    }

    if (!willRetry) {
      outputCommitCoordinator.stageEnd(stage.id)
    }
    listenerBus.post(SparkListenerStageCompleted(stage.latestInfo))
    runningStages -= stage
  }

  /**
   * Aborts all jobs depending on a particular Stage. This is called in response to a task set
   * being canceled by the TaskScheduler. Use taskSetFailed() to inject this event from outside.
   */
  private[scheduler] def abortStage(
      failedStage: Stage,
      reason: String,
      exception: Option[Throwable]): Unit = {
    if (!stageIdToStage.contains(failedStage.id)) {
      // Skip all the actions if the stage has been removed.
      return
    }
    val dependentJobs: Seq[ActiveJob] =
      activeJobs.filter(job => stageDependsOn(job.finalStage, failedStage)).toSeq
    failedStage.latestInfo.completionTime = Some(clock.getTimeMillis())
    for (job <- dependentJobs) {
      failJobAndIndependentStages(job, s"Job aborted due to stage failure: $reason", exception)
    }
    if (dependentJobs.isEmpty) {
      logInfo("Ignoring failure of " + failedStage + " because all jobs depending on it are done")
    }
  }

  /** Fails a job and all stages that are only used by that job, and cleans up relevant state. */
  private def failJobAndIndependentStages(
      job: ActiveJob,
      failureReason: String,
      exception: Option[Throwable] = None): Unit = {
    val error = new SparkException(failureReason, exception.getOrElse(null))
    var ableToCancelStages = true

    val shouldInterruptThread =
      if (job.properties == null) false
      else job.properties.getProperty(SparkContext.SPARK_JOB_INTERRUPT_ON_CANCEL, "false").toBoolean

    // Cancel all independent, running stages.
    val stages = jobIdToStageIds(job.jobId)
    if (stages.isEmpty) {
      logError("No stages registered for job " + job.jobId)
    }
    stages.foreach { stageId =>
      val jobsForStage: Option[HashSet[Int]] = stageIdToStage.get(stageId).map(_.jobIds)
      if (jobsForStage.isEmpty || !jobsForStage.get.contains(job.jobId)) {
        logError(
          "Job %d not registered for stage %d even though that stage was registered for the job"
            .format(job.jobId, stageId))
      } else if (jobsForStage.get.size == 1) {
        if (!stageIdToStage.contains(stageId)) {
          logError(s"Missing Stage for stage with id $stageId")
        } else {
          // This is the only job that uses this stage, so fail the stage if it is running.
          val stage = stageIdToStage(stageId)
          if (runningStages.contains(stage)) {
            try { // cancelTasks will fail if a SchedulerBackend does not implement killTask
              taskScheduler.cancelTasks(stageId, shouldInterruptThread)
              markStageAsFinished(stage, Some(failureReason))
            } catch {
              case e: UnsupportedOperationException =>
                logInfo(s"Could not cancel tasks for stage $stageId", e)
              ableToCancelStages = false
            }
          }
        }
      }
    }

    if (ableToCancelStages) {
      // SPARK-15783 important to cleanup state first, just for tests where we have some asserts
      // against the state.  Otherwise we have a *little* bit of flakiness in the tests.
      cleanupStateForJobAndIndependentStages(job)
      job.listener.jobFailed(error)
      listenerBus.post(SparkListenerJobEnd(job.jobId, clock.getTimeMillis(), JobFailed(error)))
    }
  }

  /** Return true if one of stage's ancestors is target. */
  private def stageDependsOn(stage: Stage, target: Stage): Boolean = {
    if (stage == target) {
      return true
    }
    val visitedRdds = new HashSet[RDD[_]]
    // We are manually maintaining a stack here to prevent StackOverflowError
    // caused by recursively visiting
    val waitingForVisit = new ArrayStack[RDD[_]]
    def visit(rdd: RDD[_]) {
      if (!visitedRdds(rdd)) {
        visitedRdds += rdd
        for (dep <- rdd.dependencies) {
          dep match {
            case shufDep: ShuffleDependency[_, _, _] =>
              val mapStage = getOrCreateShuffleMapStage(shufDep, stage.firstJobId)
              if (!mapStage.isAvailable) {
                waitingForVisit.push(mapStage.rdd)
              }  // Otherwise there's no need to follow the dependency back
            case narrowDep: NarrowDependency[_] =>
              waitingForVisit.push(narrowDep.rdd)
          }
        }
      }
    }
    waitingForVisit.push(stage.rdd)
    while (waitingForVisit.nonEmpty) {
      visit(waitingForVisit.pop())
    }
    visitedRdds.contains(target.rdd)
  }

  /**
   * Gets the locality information associated with a partition of a particular RDD.
   *
   * This method is thread-safe and is called from both DAGScheduler and SparkContext.
   *
   * @param rdd whose partitions are to be looked at
   * @param partition to lookup locality information for
   * @return list of machines that are preferred by the partition
   */
  private[spark]
  def getPreferredLocs(rdd: RDD[_], partition: Int): Seq[TaskLocation] = {
    getPreferredLocsInternal(rdd, partition, new HashSet)
  }

  /**
   * Recursive implementation for getPreferredLocs.
   *
   * This method is thread-safe because it only accesses DAGScheduler state through thread-safe
   * methods (getCacheLocs()); please be careful when modifying this method, because any new
   * DAGScheduler state accessed by it may require additional synchronization.
   */
  private def getPreferredLocsInternal(
      rdd: RDD[_],
      partition: Int,
      visited: HashSet[(RDD[_], Int)]): Seq[TaskLocation] = {
    // If the partition has already been visited, no need to re-visit.
    // This avoids exponential path exploration.  SPARK-695
    if (!visited.add((rdd, partition))) {
      // Nil has already been returned for previously visited partitions.
      return Nil
    }
    // If the partition is cached, return the cache locations
    val cached = getCacheLocs(rdd)(partition)
    if (cached.nonEmpty) {
      return cached
    }
    // If the RDD has some placement preferences (as is the case for input RDDs), get those
    val rddPrefs = rdd.preferredLocations(rdd.partitions(partition)).toList
    if (rddPrefs.nonEmpty) {
      return rddPrefs.map(TaskLocation(_))
    }

    // If the RDD has narrow dependencies, pick the first partition of the first narrow dependency
    // that has any placement preferences. Ideally we would choose based on transfer sizes,
    // but this will do for now.
    rdd.dependencies.foreach {
      case n: NarrowDependency[_] =>
        for (inPart <- n.getParents(partition)) {
          val locs = getPreferredLocsInternal(n.rdd, inPart, visited)
          if (locs != Nil) {
            return locs
          }
        }

      case _ =>
    }

    Nil
  }

  /** Mark a map stage job as finished with the given output stats, and report to its listener. */
  def markMapStageJobAsFinished(job: ActiveJob, stats: MapOutputStatistics): Unit = {
    // In map stage jobs, we only create a single "task", which is to finish all of the stage
    // (including reusing any previous map outputs, etc); so we just mark task 0 as done
    job.finished(0) = true
    job.numFinished += 1
    job.listener.taskSucceeded(0, stats)
    cleanupStateForJobAndIndependentStages(job)
    listenerBus.post(SparkListenerJobEnd(job.jobId, clock.getTimeMillis(), JobSucceeded))
  }

  def stop() {
    messageScheduler.shutdownNow()
    eventProcessLoop.stop()
    taskScheduler.stop()
  }

  eventProcessLoop.start()
}

private[scheduler] class DAGSchedulerEventProcessLoop(dagScheduler: DAGScheduler)
  extends EventLoop[DAGSchedulerEvent]("dag-scheduler-event-loop") with Logging {

  private[this] val timer = dagScheduler.metricsSource.messageProcessingTimer

  /**
   * The main event loop of the DAG scheduler.
   */
  override def onReceive(event: DAGSchedulerEvent): Unit = {
    val timerContext = timer.time()
    try {
      doOnReceive(event)
    } finally {
      timerContext.stop()
    }
  }

  private def doOnReceive(event: DAGSchedulerEvent): Unit = event match {
    case JobSubmitted(jobId, rdd, func, partitions, callSite, listener, properties) =>
      dagScheduler.handleJobSubmitted(jobId, rdd, func, partitions, callSite, listener, properties)

    case MapStageSubmitted(jobId, dependency, callSite, listener, properties) =>
      dagScheduler.handleMapStageSubmitted(jobId, dependency, callSite, listener, properties)

    case StageCancelled(stageId, reason) =>
      dagScheduler.handleStageCancellation(stageId, reason)

    case JobCancelled(jobId, reason) =>
      dagScheduler.handleJobCancellation(jobId, reason)

    case JobGroupCancelled(groupId) =>
      dagScheduler.handleJobGroupCancelled(groupId)

    case AllJobsCancelled =>
      dagScheduler.doCancelAllJobs()

    case ExecutorAdded(execId, host) =>
      dagScheduler.handleExecutorAdded(execId, host)

    case ExecutorLost(execId, reason) =>
      val workerLost = reason match {
        case SlaveLost(_, true) => true
        case _ => false
      }
      dagScheduler.handleExecutorLost(execId, workerLost)

    case WorkerRemoved(workerId, host, message) =>
      dagScheduler.handleWorkerRemoved(workerId, host, message)

    case BeginEvent(task, taskInfo) =>
      dagScheduler.handleBeginEvent(task, taskInfo)

    case SpeculativeTaskSubmitted(task) =>
      dagScheduler.handleSpeculativeTaskSubmitted(task)

    case GettingResultEvent(taskInfo) =>
      dagScheduler.handleGetTaskResult(taskInfo)

    case completion: CompletionEvent =>
      dagScheduler.handleTaskCompletion(completion)

    case TaskSetFailed(taskSet, reason, exception) =>
      dagScheduler.handleTaskSetFailed(taskSet, reason, exception)

    case ResubmitFailedStages =>
      dagScheduler.resubmitFailedStages()
  }

  override def onError(e: Throwable): Unit = {
    logError("DAGSchedulerEventProcessLoop failed; shutting down SparkContext", e)
    try {
      dagScheduler.doCancelAllJobs()
    } catch {
      case t: Throwable => logError("DAGScheduler failed to cancel all jobs.", t)
    }
    dagScheduler.sc.stopInNewThread()
  }

  override def onStop(): Unit = {
    // Cancel any active jobs in postStop hook
    dagScheduler.cleanUpAfterSchedulerStop()
  }
}

private[spark] object DAGScheduler {
  // The time, in millis, to wait for fetch failure events to stop coming in after one is detected;
  // this is a simplistic way to avoid resubmitting tasks in the non-fetchable map stage one by one
  // as more failure events come in
  val RESUBMIT_TIMEOUT = 200

  // Number of consecutive stage attempts allowed before a stage is aborted
  val DEFAULT_MAX_CONSECUTIVE_STAGE_ATTEMPTS = 4
}
