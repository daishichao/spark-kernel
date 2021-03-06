/*
 * Copyright 2014 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ibm.spark.kernel.protocol.v5.relay

import akka.actor._
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.ibm.spark.kernel.protocol.v5._
import com.ibm.spark.kernel.protocol.v5.Utilities._
import org.mockito.Mockito._
import org.scalatest.concurrent.{PatienceConfiguration, ScalaFutures}
import org.scalatest.mock.MockitoSugar
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{BeforeAndAfter, FunSpecLike, Matchers}
import org.mockito.Matchers.{eq => mockEq}
import org.mockito.AdditionalMatchers.{not => mockNot}
import scala.concurrent.duration._
import com.ibm.spark.kernel.protocol.v5.MessageType._
import com.ibm.spark.kernel.protocol.v5.KernelMessage
import akka.zeromq.ZMQMessage
import scala.concurrent._
import akka.pattern.pipe
import scala.util.Random
import ExecutionContext.Implicits.global

class KernelMessageRelaySpec extends TestKit(ActorSystem("RelayActorSystem"))
  with ImplicitSender with FunSpecLike with Matchers with MockitoSugar
  with BeforeAndAfter with ScalaFutures {
  private val IncomingMessageType = CompleteRequest.toString
  private val OutgoingMessageType = CompleteReply.toString

  private val header: Header = Header("<UUID>", "<USER>", "<SESSION>",
    "<TYPE>", "<VERSION>")
  private val parentHeader: Header = Header("<PARENT-UUID>", "<PARENT-USER>",
    "<PARENT-SESSION>", "<PARENT-TYPE>", "<PARENT-VERSION>")
  private val incomingKernelMessage: KernelMessage = KernelMessage(Seq("<ID>"),
    "<SIGNATURE>", header.copy(msg_type = IncomingMessageType),
    parentHeader, Metadata(), "<CONTENT>")
  private val outgoingKernelMessage: KernelMessage = KernelMessage(Seq("<ID>"),
    "<SIGNATURE>", header.copy(msg_type = OutgoingMessageType),
    incomingKernelMessage.header, Metadata(), "<CONTENT>")
  private val incomingZmqStrings = "1" :: "2" :: "3" :: "4" :: Nil

  private var actorLoader: ActorLoader = _
  private var signatureProbe: TestProbe = _
  private var signatureSelection: ActorSelection = _
  private var captureProbe: TestProbe = _
  private var captureSelection: ActorSelection = _
  private var handlerProbe: TestProbe = _
  private var handlerSelection: ActorSelection = _
  private var relayWithoutSignatureManager: ActorRef = _
  private var relayWithSignatureManager: ActorRef = _

  before {
    // Create a mock ActorLoader for the Relay we are going to test
    actorLoader = mock[ActorLoader]

    // Create a probe for the signature manager and mock the ActorLoader to
    // return the associated ActorSelection
    signatureProbe = TestProbe()
    signatureSelection = system.actorSelection(signatureProbe.ref.path.toString)
    when(actorLoader.load(SystemActorType.SignatureManager))
      .thenReturn(signatureSelection)

    // Create a probe to capture output from the relay for testing
    captureProbe = TestProbe()
    captureSelection = system.actorSelection(captureProbe.ref.path.toString)
    when(actorLoader.load(mockNot(mockEq(SystemActorType.SignatureManager))))
      .thenReturn(captureSelection)

    relayWithoutSignatureManager = system.actorOf(Props(
      classOf[KernelMessageRelay], actorLoader, false
    ))

    relayWithSignatureManager = system.actorOf(Props(
      classOf[KernelMessageRelay], actorLoader, true
    ))
  }

  describe("Relay") {
    describe("#receive") {
      describe("when not using the signature manager") {
        it("should not send anything to SignatureManager for incoming") {
          relayWithoutSignatureManager ! true // Mark as ready for incoming
          relayWithoutSignatureManager ! incomingKernelMessage
          signatureProbe.expectNoMsg(25.millis)
        }

        it("should not send anything to SignatureManager for outgoing") {
          relayWithoutSignatureManager ! outgoingKernelMessage
          signatureProbe.expectNoMsg(25.millis)
        }

        it("should relay KernelMessage for incoming") {
          relayWithoutSignatureManager ! true // Mark as ready for incoming
          relayWithoutSignatureManager !
            ((incomingZmqStrings, incomingKernelMessage))
          captureProbe.expectMsg(incomingKernelMessage)
        }

        it("should relay KernelMessage for outgoing") {
          relayWithoutSignatureManager ! outgoingKernelMessage
          captureProbe.expectMsg(outgoingKernelMessage)
        }
      }

      describe("when using the signature manager") {
        it("should verify the signature if the message is incoming") {
          relayWithSignatureManager ! true // Mark as ready for incoming
          relayWithSignatureManager ! incomingKernelMessage
          signatureProbe.expectMsg(incomingKernelMessage)
        }

        it("should construct the signature if the message is outgoing") {
          relayWithSignatureManager ! outgoingKernelMessage
          signatureProbe.expectMsg(outgoingKernelMessage)
        }
      }

      describe("when not ready") {
        it("should not relay the message if it is incoming") {
          val incomingMessage: ZMQMessage = incomingKernelMessage

          relayWithoutSignatureManager ! incomingMessage
          captureProbe.expectNoMsg(25.millis)
        }

        it("should relay the message if it is outgoing") {
          relayWithoutSignatureManager ! outgoingKernelMessage
          captureProbe.expectMsg(outgoingKernelMessage)
        }
      }

      describe("when ready") {
        it("should relay the message if it is incoming") {
          relayWithoutSignatureManager ! true // Mark as ready for incoming
          relayWithoutSignatureManager !
            ((incomingZmqStrings, incomingKernelMessage))
          captureProbe.expectMsg(incomingKernelMessage)
        }

        it("should relay the message if it is outgoing") {
          relayWithoutSignatureManager ! true // Mark as ready for incoming
          relayWithoutSignatureManager ! outgoingKernelMessage
          captureProbe.expectMsg(outgoingKernelMessage)
        }
      }

      describe("multiple messages in order"){
        it("should relay messages in the order they were received") {
          //  Setup the base actor system and the relay
          val actorLoader = mock[ActorLoader]
          val kernelMessageRelay = system.actorOf(Props(
            classOf[KernelMessageRelay], actorLoader, true
          ))
          //  Where all of the messages are relayed to, otherwise NPE
          val captureProbe = TestProbe()
          val captureSelection = system.actorSelection(captureProbe.ref.path.toString)
          when(actorLoader.load(CompleteRequest)).thenReturn(captureSelection)


          val n = 5
          val chaoticPromise: Promise[String] = Promise()
          var actual : List[String] = List()
          val expected = (0 until n).map(_.toString).toList

          // setup a ChaoticActor to accumulate message values
          // A promise succeeds after n messages have been accumulated
          val chaoticActor: ActorRef = system.actorOf(Props(
            classOf[ChaoticActor[Boolean]],
            (paramVal: Any) => {
              val tuple = paramVal.asInstanceOf[(String, Seq[_])]
              actual = actual :+ tuple._1
              if (actual.length == n) chaoticPromise.success("Done")
              true
            }
          ))

          when(actorLoader.load(SystemActorType.SignatureManager))
            .thenReturn(system.actorSelection(chaoticActor.path))

          kernelMessageRelay ! true

          // Sends messages with contents = to values of increasing counter
          sendKernelMessages(n, kernelMessageRelay)
          // Message values should be accumulated in the proper order
          whenReady(chaoticPromise.future,
            PatienceConfiguration.Timeout(Span(3, Seconds)),
            PatienceConfiguration.Interval(Span(100, Millis))) {
            case _: String =>
              actual should be(expected)
          }

        }
      }
    }
  }
  def sendKernelMessages(n: Int, kernelMessageRelay: ActorRef): Unit ={
    // Sends n messages to the relay
    (0 until n).foreach (i => {
      val km = KernelMessage(Seq("<ID>"), s"${i}",
        header.copy(msg_type = IncomingMessageType), parentHeader,
        Metadata(), s"${i}")
      kernelMessageRelay ! Tuple2(Seq("SomeString"), km)
    })

  }
}


case class ChaoticActor[U](receiveFunc : Any => U) extends Actor {
  override def receive: Receive = {
    case fVal: Any =>
      //  The test actor system runs the actors on a single thread, so we must
      //  simulate asynchronous behaviour by staring a new thread
      val promise = Promise[U]()
      promise.future pipeTo sender
      new Thread(new Runnable {
        override def run(): Unit = {
          Thread.sleep(Random.nextInt(30) * 10)
          promise.success(receiveFunc(fVal))
        }
      }).start()
  }
}