/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.containerpool.docker.test

import common.StreamLogging
import common.TimingHelpers
import common.WskActorSystem
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.containerpool.ContainerAddress
import whisk.core.containerpool.ContainerArgsConfig
import whisk.core.containerpool.ContainerId
import whisk.core.containerpool.docker.DockerApiWithFileAccess
import whisk.core.containerpool.docker.DockerContainerFactory
import whisk.core.containerpool.docker.DockerContainerFactoryConfig
import whisk.core.containerpool.docker.RuncApi
import whisk.core.entity.ExecManifest
import whisk.core.entity.InvokerInstanceId
import whisk.core.entity.size._

@RunWith(classOf[JUnitRunner])
class DockerContainerFactoryTests
    extends FlatSpec
    with Matchers
    with MockFactory
    with StreamLogging
    with BeforeAndAfterEach
    with WskActorSystem
    with TimingHelpers {

  implicit val config = new WhiskConfig(ExecManifest.requiredProperties)
  ExecManifest.initialize(config) should be a 'success

  behavior of "DockerContainerFactory"

  it should "set the docker run args based on ContainerArgsConfig" in {

    val image = ExecManifest.runtimesManifest.manifests("nodejs").image

    implicit val tid = TransactionId.testing
    val dockerApiStub = mock[DockerApiWithFileAccess]
    //setup run expectation
    (dockerApiStub
      .run(_: String, _: Seq[String])(_: TransactionId))
      .expects(
        image.localImageName(config.runtimesRegistry),
        List(
          "--cpu-shares",
          "32", //should be calculated as 1024/(numcore * sharefactor) via ContainerFactory.cpuShare
          "--memory",
          "10m",
          "--memory-swap",
          "10m",
          "--network",
          "net1",
          "-e",
          "__OW_API_HOST=://:",
          "--dns",
          "dns1",
          "--dns",
          "dns2",
          "--name",
          "testContainer",
          "--env",
          "e1",
          "--env",
          "e2"),
        *)
      .returning(Future.successful { ContainerId("fakecontainerid") })
    //setup inspect expectation
    (dockerApiStub
      .inspectIPAddress(_: ContainerId, _: String)(_: TransactionId))
      .expects(ContainerId("fakecontainerid"), "net1", *)
      .returning(Future.successful { ContainerAddress("1.2.3.4", 1234) })
    //setup rm expectation
    (dockerApiStub
      .rm(_: ContainerId)(_: TransactionId))
      .expects(ContainerId("fakecontainerid"), *)
      .returning(Future.successful(Unit))

    val factory =
      new DockerContainerFactory(
        InvokerInstanceId(0),
        Map(),
        ContainerArgsConfig("net1", Seq("dns1", "dns2"), Map("env" -> Set("e1", "e2"))),
        DockerContainerFactoryConfig(true))(actorSystem, executionContext, logging, dockerApiStub, mock[RuncApi])

    val cf = factory.createContainer(tid, "testContainer", image, false, 10.MB, 32)

    val c = Await.result(cf, 5000.milliseconds)

    Await.result(c.destroy(), 500.milliseconds)

  }

}
