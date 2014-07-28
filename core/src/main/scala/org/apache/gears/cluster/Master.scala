package org.apache.gears.cluster

/**
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
import akka.actor._
import akka.remote.RemoteScope
import org.apache.gearpump._
import org.apache.gearpump.service.SimpleKVService
import org.apache.gearpump.util.ActorSystemBooter.{BindLifeCycle, RegisterActorSystem}
import org.apache.gears.cluster.AppMasterToMaster._
import org.apache.gears.cluster.ClientToMaster._
import org.apache.gears.cluster.MasterToAppMaster._
import org.apache.gears.cluster.MasterToWorker._
import org.apache.gears.cluster.WorkerToMaster._
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable.Queue

object Master {
  private val LOG: Logger = LoggerFactory.getLogger(classOf[Master])
}

class Master extends Actor {
  import org.apache.gears.cluster.Master._

  private var resources = new Array[(ActorRef, Int)](0)
  private val resourceRequests = new Queue[(ActorRef, Int)]

  private var appManager : ActorRef = null
  private var workerId = 0;

  override def receive : Receive = workerMsgHandler orElse appMasterMsgHandler orElse clientMsgHandler orElse  ActorUtil.defaultMsgHandler(self)

  def workerMsgHandler : Receive = {
    //create worker
    case RegisterActorSystem(systemPath) =>
      LOG.info(s"Received RegisterActorSystem $systemPath")
      val systemAddress = AddressFromURIString(systemPath)
      val workerConfig = Props(classOf[Worker], workerId, self).withDeploy(Deploy(scope = RemoteScope(systemAddress)))
      val worker = context.actorOf(workerConfig, classOf[Worker].getSimpleName + workerId)
      workerId += 1
      sender ! BindLifeCycle(worker)
    case RegisterWorker(id) =>
      context.watch(sender)
      sender ! WorkerRegistered
      LOG.info(s"Register Worker $id....")
    case ResourceUpdate(id, slots) =>
      LOG.info(s"Resource update id: $id, slots: $slots....")
      val current = sender;
      val index = resources.indexWhere((worker) => worker._1.equals(current), 0)
      if (index == -1) {
        resources = resources :+ (current, slots)
      } else {
        resources(index) = (current, slots)
      }
      allocateResource
  }

  def appMasterMsgHandler : Receive = {
    case RequestResource(appId, slots) =>
      LOG.info(s"Request resource: appId: $appId, slots: $slots")
      val appMaster = sender
      resourceRequests.enqueue((appMaster, slots))
      allocateResource
  }

  def clientMsgHandler : Receive = {
    case app : SubmitApplication => {
      LOG.info(s"Receive from client, SubmitApplication $app")
      appManager.forward(app)
    }
    case app : ShutdownApplication => {
      LOG.info(s"Receive from client, Shutting down Application ${app.appId}")
      appManager.forward(app)
    }
    case Shutdown =>
      LOG.info(s"Shutting down Master called from ${sender.toString}...")
      context.stop(self)
  }

  // shutdown the hosting actor system
  override def postStop(): Unit = context.system.shutdown()

  def allocateResource : Unit = {
    val length = resources.length
    val flattenResource = resources.zipWithIndex.flatMap((workerWithIndex) => {
      val ((worker, slots), index) = workerWithIndex
      0.until(slots).map((seq) => (worker, seq * length + index))
    }).asInstanceOf[Array[(ActorRef, Int)]].sortBy(_._2).map(_._1)

    val total = flattenResource.length
    def assignResourceToApplication(allocated : Int) : Unit = {
      if (allocated == total || resourceRequests.isEmpty) {
        return
      }

      val (appMaster, slots) = resourceRequests.dequeue()
      val newAllocated = Math.min(total - allocated, slots)
      val singleAllocation = flattenResource.slice(allocated, allocated + newAllocated)
        .groupBy((actor) => actor).mapValues(_.length).toArray.map((resource) => Resource(resource._1, resource._2))
      appMaster ! ResourceAllocated(singleAllocation)
      if (slots > newAllocated) {
        resourceRequests.enqueue((appMaster, slots - newAllocated))
      }
      assignResourceToApplication(allocated + newAllocated)
    }

    assignResourceToApplication(0)
  }

  override def preStart(): Unit = {
    val path = ActorUtil.getFullPath(context)
    LOG.info(s"master path is $path")
    SimpleKVService.set("master", path)
    appManager = context.actorOf(Props[AppManager])
  }
}

