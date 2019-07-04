/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.scheduler.cluster

import java.util.concurrent.atomic.AtomicInteger

import org.apache.spark.internal.Logging
import org.apache.spark.ps.cluster.Message
import org.apache.spark.ps.cluster.Message.{CreateOrRemovePythonCondaEnvResponse, CreateOrRemovePythonCondaEnvResponseItem}
import org.apache.spark.rpc._
import org.apache.spark.util.{ThreadUtils, Utils}
import org.apache.spark.{MLSQLConf, SparkConf, SparkContext}

import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, HashMap}
import scala.concurrent.duration._

/**
  * Created by allwefantasy on 31/1/2018.
  */
class PSDriverEndpoint(sc: SparkContext, override val rpcEnv: RpcEnv)
  extends ThreadSafeRpcEndpoint with Logging {
  protected val addressToExecutorId = new HashMap[RpcAddress, String]
  private val executorDataMap = new HashMap[String, ExecutorData]()

  //  private var sparkExecutorDataMap = new HashMap[String, ExecutorData]()
  //  private val refreshThread =
  ThreadUtils.newDaemonSingleThreadScheduledExecutor("ps-driver-refresh-thread")

  override def onStart() {
    // Periodically revive offers to allow delay scheduling to work
    logInfo("started PSDriverEndpoint")
  }

  override def receive: PartialFunction[Any, Unit] = {
    case Message.Pong(id) =>
      logInfo(s"received message ${Message.Pong} from executor ${id}!")
    case Message.Ping =>
      ping
  }

  override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    case Message.RegisterPSExecutor(executorId, executorRef, hostname, cores, logUrls) =>
      if (executorDataMap.contains(executorId)) {
        executorRef.send(Message.RegisterExecutorFailed("Duplicate executor ID: " + executorId))
        context.reply(true)
      } else {
        val executorAddress = if (executorRef.address != null) {
          executorRef.address
        } else {
          context.senderAddress
        }
        logInfo(s"Registered ps-executor $executorRef ($executorAddress) with ID $executorId")
        addressToExecutorId(executorAddress) = executorId

        val data = new ExecutorData(executorRef, executorRef.address, hostname,
          cores, cores, logUrls)
        executorDataMap.put(executorId, data)
        executorRef.send(Message.RegisteredExecutor)
        // Note: some tests expect the reply to come after we put the executor in the map
        context.reply(true)
      }
    case Message.CopyModelToLocal(modelPath, destPath) =>
      val ks = sc.getExecutorIds().toSet
      val counter = new AtomicInteger(0)
      filterDuplicateHost.par.foreach { ed =>
        if (ks.contains(ed._1)) {
          ed._2.executorEndpoint.askSync[Boolean](Message.CopyModelToLocal(modelPath, destPath), PSDriverEndpoint.MLSQL_DEFAULT_RPC_TIMEOUT(sc.conf))
          counter.incrementAndGet()
        }
      }
      context.reply(true)
    case Message.CreateOrRemovePythonCondaEnv(condaYamlFile, options, command) => {
      val ks = sc.getExecutorIds().toSet
      val counter = new AtomicInteger(0)

      val resBuffer = ArrayBuffer[CreateOrRemovePythonCondaEnvResponseItem]()
      filterDuplicateHost.par.foreach { ed =>
        if (ks.contains(ed._1)) {
          val res = CreateOrRemovePythonCondaEnvResponseItem(false, ed._2.executorHost, System.currentTimeMillis(), 0, "")
          logInfo(s"PythonEnv[${condaYamlFile}]: Prepare python env in ${ed._2.executorHost} ")
          var msg = ""
          val success = try {
            ed._2.executorEndpoint.askSync[Boolean](Message.CreateOrRemovePythonCondaEnv(condaYamlFile, options, command), PSDriverEndpoint.MLSQL_DEFAULT_RPC_TIMEOUT(sc.conf))
          } catch {
            case e: Exception =>
              logError("PythonEnv create exception", e)
              msg = e.getMessage
              false
          }
          counter.incrementAndGet()
          resBuffer += res.copy(success = success, endTime = System.currentTimeMillis(), msg = msg)
          logInfo(s"PythonEnv[${condaYamlFile}]: Finish prepare python env in ${ed._2.executorHost}")
          logInfo(s"PythonEnv[${condaYamlFile}]: process: ${counter.get()}  total: ${filterDuplicateHost.size}")
        }
      }
      context.reply(CreateOrRemovePythonCondaEnvResponse(condaYamlFile, resBuffer, filterDuplicateHost.size))
    }
    case Message.Ping =>
      ping
      context.reply(true)
  }

  private def filterDuplicateHost = {
    val hostMap = new mutable.HashMap[String, (String, ExecutorData)]()

    executorDataMap.foreach { f =>
      if (!hostMap.contains(f._2.executorHost)) {
        hostMap.put(f._2.executorHost, (f._1, f._2))
      }
    }

    hostMap.values
  }

  private def ping: Unit = {
    logInfo("received ping message")
    val ks = sc.getExecutorIds().toSet
    executorDataMap.foreach { ed =>
      if (ks.contains(ed._1)) {
        val response = ed._2.executorEndpoint.askSync[Message.Pong](Message.Ping)
        self.send(response)
      }
    }

  }


}

object PSDriverEndpoint {
  def MLSQL_DEFAULT_RPC_TIMEOUT(conf: SparkConf) = {
    val timeout = {
      Utils.timeStringAsSeconds("3600s").seconds
    }
    new RpcTimeout(timeout, MLSQLConf.MLSQL_PS_ASK_TIMEOUT.key)
  }
}
