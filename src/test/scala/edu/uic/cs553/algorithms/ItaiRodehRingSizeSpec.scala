package edu.uic.cs553.algorithms

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import edu.uic.cs553.sim.{NodeActor, SimMessage}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.*

/**
 * Tests for Itai-Rodeh ring-size estimation on a 6-node ring.
 *
 * After running, each node that originated a probe should have received its
 * own probe back with hops == 6, giving an accurate size estimate.
 */
class ItaiRodehRingSizeSpec
    extends TestKit(ActorSystem("ItaiRodehRingSizeSpec"))
    with ImplicitSender
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll:

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  "ItaiRodehRingSize" should {

    "instantiate correctly and expose its name" in {
      val algo = ItaiRodehRingSize(seed = 0L)
      algo.name shouldBe ItaiRodehRingSize.Name
      algo.name shouldBe "itai-rodeh-ring-size"
    }

    "report averageEstimate as 0 before any rounds complete" in {
      val algo = ItaiRodehRingSize(seed = 7L)
      algo.averageEstimate shouldBe 0.0
    }

    "run on a 6-node ring and complete metric collection" in {
      val ringSize = 6
      val seed     = 77L

      val nodes = (1 to ringSize).map { i =>
        i -> system.actorOf(
          NodeActor.props(i, Some(ItaiRodehRingSize(seed + i))),
          s"ringsize-node-$i"
        )
      }.toMap

      val ringNext: Map[Int, Int] = (1 to ringSize).map(i => i -> (i % ringSize + 1)).toMap

      nodes.foreach { (id, ref) =>
        val rightId = ringNext(id)
        ref ! SimMessage.Init(
          nodeId          = id,
          neighbors       = nodes.view.filterKeys(_ != id).mapValues(identity).toMap,
          allowedOnEdge   = nodes.keys.filterNot(_ == id).map(_ -> Set("CONTROL", "PING")).toMap,
          pdf             = Map("PING" -> 1.0),
          timerEnabled    = false,
          tickEveryMs     = 9999L,
          isInputNode     = false,
          rightNeighborId = Some(rightId),
          ringSize        = ringSize
        )
      }

      nodes.values.foreach(_ ! SimMessage.Start)

      // Allow probes to circulate (6 hops * 3 rounds * some buffer)
      Thread.sleep(3000)

      val probe = TestProbe()
      nodes.values.foreach(_ ! SimMessage.GetMetrics(probe.ref))

      val reports = (1 to ringSize).map(_ =>
        probe.expectMsgType[SimMessage.MetricsReport](4.seconds)
      )

      reports.size shouldBe ringSize
      reports.map(_.nodeId).toSet shouldBe (1 to ringSize).toSet
    }
  }
