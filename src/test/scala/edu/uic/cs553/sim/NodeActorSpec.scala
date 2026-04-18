package edu.uic.cs553.sim

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.*

class NodeActorSpec
    extends TestKit(ActorSystem("NodeActorSpec"))
    with ImplicitSender
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll:

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  private def makeInit(
    nodeId: Int,
    timerEnabled: Boolean = false
  ): SimMessage.Init =
    SimMessage.Init(
      nodeId          = nodeId,
      neighbors       = Map.empty,
      allowedOnEdge   = Map.empty,
      pdf             = Map("PING" -> 0.6, "GOSSIP" -> 0.4),
      timerEnabled    = timerEnabled,
      tickEveryMs     = 5000L,
      isInputNode     = false,
      rightNeighborId = None,
      ringSize        = 1
    )

  "NodeActor" should {

    "reply to GetMetrics after Stop with empty counts if no messages arrived" in {
      val probe = TestProbe()
      val node  = system.actorOf(NodeActor.props(99, None))

      node ! makeInit(99)
      node ! SimMessage.Start
      node ! SimMessage.Stop
      node ! SimMessage.GetMetrics(probe.ref)

      val report = probe.expectMsgType[SimMessage.MetricsReport](3.seconds)
      report.nodeId shouldBe 99
    }

    "count incoming Envelope messages by type" in {
      val probe = TestProbe()
      val node  = system.actorOf(NodeActor.props(7, None))

      node ! makeInit(7)
      node ! SimMessage.Start
      node ! SimMessage.Envelope(1, "PING", "hello")
      node ! SimMessage.Envelope(2, "PING", "world")
      node ! SimMessage.Envelope(3, "GOSSIP", "buzz")
      node ! SimMessage.Stop
      node ! SimMessage.GetMetrics(probe.ref)

      val report = probe.expectMsgType[SimMessage.MetricsReport](3.seconds)
      report.msgCountsByType.getOrElse("PING", 0L)   shouldBe 2L
      report.msgCountsByType.getOrElse("GOSSIP", 0L) shouldBe 1L
    }

    "route ExternalInput only to neighbours that allow the message type" in {
      val neighbour = TestProbe()
      val probe     = TestProbe()
      val node      = system.actorOf(NodeActor.props(5, None))

      val init = SimMessage.Init(
        nodeId          = 5,
        neighbors       = Map(10 -> neighbour.ref),
        allowedOnEdge   = Map(10 -> Set("PING")),
        pdf             = Map("PING" -> 1.0),
        timerEnabled    = false,
        tickEveryMs     = 9999L,
        isInputNode     = true,
        rightNeighborId = None,
        ringSize        = 2
      )
      node ! init
      node ! SimMessage.Start
      node ! SimMessage.ExternalInput("PING", "test")

      // Neighbour should receive an Envelope from the node
      neighbour.expectMsgType[SimMessage.Envelope](2.seconds)
    }

    "ignore AlgoMessage for unregistered algorithm" in {
      val probe = TestProbe()
      val node  = system.actorOf(NodeActor.props(3, None))

      node ! makeInit(3)
      node ! SimMessage.Start
      // This message should not crash the actor
      node ! SimMessage.AlgoMessage("unknown-algo", "TEST", Map.empty)
      node ! SimMessage.GetMetrics(probe.ref)

      probe.expectMsgType[SimMessage.MetricsReport](2.seconds)
    }
  }
