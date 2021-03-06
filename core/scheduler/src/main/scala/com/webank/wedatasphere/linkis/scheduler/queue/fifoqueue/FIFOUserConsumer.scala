/*
 * Copyright 2019 WeBank
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.webank.wedatasphere.linkis.scheduler.queue.fifoqueue

/**
  * Created by enjoyyin on 2018/9/7.
  */

import java.util.concurrent.{ExecutorService, Future}

import com.webank.wedatasphere.linkis.common.exception.{ErrorException, WarnException}
import com.webank.wedatasphere.linkis.common.utils.Utils
import com.webank.wedatasphere.linkis.scheduler.SchedulerContext
import com.webank.wedatasphere.linkis.scheduler.exception.SchedulerErrorException
import com.webank.wedatasphere.linkis.scheduler.executer.Executor
import com.webank.wedatasphere.linkis.scheduler.future.{BDPFuture, BDPFutureTask}
import com.webank.wedatasphere.linkis.scheduler.queue._

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.TimeoutException

class FIFOUserConsumer(schedulerContext: SchedulerContext,
                       executeService: ExecutorService, private var group: Group) extends Consumer(schedulerContext, executeService) {
  private var fifoGroup = group.asInstanceOf[FIFOGroup]
  private var queue: ConsumeQueue = _
  private val maxRunningJobsNum = fifoGroup.getMaxRunningJobs
  private val runningJobs = new Array[Job](maxRunningJobsNum)
  private var future: Future[_] = _

  def this(schedulerContext: SchedulerContext,executeService: ExecutorService) = {
    this(schedulerContext,executeService, null)
  }

  def start(): Unit = future = executeService.submit(this)

  override def setConsumeQueue(consumeQueue: ConsumeQueue) = {
    queue = consumeQueue
  }

  override def getConsumeQueue = queue

  override def getGroup = fifoGroup

  override def setGroup(group: Group) = {
    this.fifoGroup = group.asInstanceOf[FIFOGroup]
  }

  override def getRunningEvents = getEvents(_.isRunning)

  private def getEvents(op: SchedulerEvent => Boolean): Array[SchedulerEvent] = {
    val result = ArrayBuffer[SchedulerEvent]()
    runningJobs.filter(_ != null).filter(x => op(x)).foreach(result += _)
    result.toArray
  }

  override def run() = {
    Thread.currentThread().setName(s"${toString}Thread")
    info(s"$toString thread started!")
    while (!terminate) {
      Utils.tryAndError(loop())
      Utils.tryAndError(Thread.sleep(10))
    }
    info(s"$toString thread stopped!")
  }

  protected def askExecutorGap(): Unit = {}

  protected def loop(): Unit = {
    val completedNums = runningJobs.filter(e => e == null || e.isCompleted)
    if (completedNums.length < 1) {
      Utils.tryQuietly(Thread.sleep(1000))  //TODO can also be optimized to optimize by implementing JobListener(TODO 还可以优化，通过实现JobListener进行优化)
      return
    }
    var isRetryJob = false
    var event: Option[SchedulerEvent] = None
    def getWaitForRetryEvent: Option[SchedulerEvent] = {
      val waitForRetryJobs = runningJobs.filter(job => job != null && job.isJobCanRetry)
      waitForRetryJobs.find{job =>
        isRetryJob = Utils.tryCatch(job.turnToRetry()){ t =>
          job.onFailure("Job state flipped to Scheduled failed in Retry(Retry时，job状态翻转为Scheduled失败)！", t)
          false
        }
        isRetryJob
      }
    }
    while(event.isEmpty) {
      val takeEvent = if(getRunningEvents.isEmpty) Option(queue.take()) else queue.take(3000)
      event = if(takeEvent.exists(e => Utils.tryCatch(e.turnToScheduled()) {t =>
          takeEvent.get.asInstanceOf[Job].onFailure("Job state flipped to Scheduled failed(Job状态翻转为Scheduled失败)！", t)
          false
      })) takeEvent else getWaitForRetryEvent
    }
    event.foreach { case job: Job =>
      Utils.tryCatch {
        val (totalDuration, askDuration) = (fifoGroup.getMaxAskExecutorDuration, fifoGroup.getAskExecutorInterval)
        var executor: Option[Executor] = None
        job.consumerFuture = new BDPFutureTask(this.future)
        Utils.waitUntil(() => {
          executor = Utils.tryCatch(schedulerContext.getOrCreateExecutorManager.askExecutor(job, askDuration)) {
            case warn: WarnException =>
              job.getLogListener.foreach(_.onLogUpdate(job, warn.getDesc))
              None
            case e:ErrorException =>
              job.getLogListener.foreach(_.onLogUpdate(job, e.getDesc))
              throw e
            case error: Throwable =>
              throw error
          }
          Utils.tryQuietly(askExecutorGap())
          executor.isDefined
        }, totalDuration)
        job.consumerFuture = null
        executor.foreach { executor =>
          job.setExecutor(executor)
          job.future = executeService.submit(job)
          job.getJobDaemon.foreach(jobDaemon => jobDaemon.future = executeService.submit(jobDaemon))
          if(!isRetryJob) putToRunningJobs(job)
        }
      }{
        case _: TimeoutException =>
          warn(s"Ask executor for Job $job timeout!")
          job.onFailure("The request engine times out and the cluster cannot provide enough resources(请求引擎超时，集群不能提供足够的资源).",
            new SchedulerErrorException(11055, "Insufficient resources, requesting available engine timeout(资源不足，请求可用引擎超时)！"))
        case error: Throwable =>
          job.onFailure("Request engine failed, possibly due to insufficient resources or background process error(请求引擎失败，可能是由于资源不足或后台进程错误)!", error)
          if(job.isWaitForRetry) {
            warn(s"Ask executor for Job $job failed, wait for the next retry!", error)
            if(!isRetryJob)  putToRunningJobs(job)
          } else warn(s"Ask executor for Job $job failed!", error)
      }
    }
  }

  private def putToRunningJobs(job: Job): Unit = {
    val index = runningJobs.indexWhere(f => f == null || f.isCompleted)
    runningJobs(index) = job
  }

  override def shutdown() = {
    future.cancel(true)
    super.shutdown()
  }
}
