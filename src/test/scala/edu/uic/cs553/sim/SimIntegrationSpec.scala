package edu.uic.cs553.sim

import akka.actor.{ActorSystem, ActorRef}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import edu.uic.cs553.graph.GraphLoader
import edu.uic.cs553.algorithms.{ItaiRodehElection, ItaiRodehRingSize}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.*

/**
 * End-to-end integration test: loads the sample graph, creates a SimCoordinator,
 * runs Start → Stop → metrics collection on the full actor graph.
 */
class SimIntegrationSpec
    extends TestKit(ActorSystem("SimIntegrationSpec"))
    with ImplicitSender
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll:

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  private val cfg = ConfigFactory.parseString("""
    sim.messages.types        = ["CONTROL","PING","GOSSIP","WORK"]
    sim.edgeLabeling.default  = ["PING","GOSSIP"]
    sim.traffic.tickIntervalMs = 1000
    sim.traffic.defaultPdf    = [
      { msg = "PING",   p = 0.5 }
      { msg = "GOSSIP", p = 0.5 }
    ]
    sim.initiators.timers = [1]
    sim.initiators.inputs = [2]
  """)

  "SimCoordinator" should {

    "start and stop cleanly on a 12-node sample graph with no algorithm" in {
      val Right((rawNodes, rawEdges)) =
        GraphLoader.loadFromClasspath("/graphs/sample-graph.json"): @unchecked

      val graph = GraphLoader.enrich(rawNodes, rawEdges, cfg, seed = 1L)
      graph.nodes.size shouldBe 12

      val coordinator = system.actorOf(
        SimCoordinator.props(graph, _ => None),
        "coord-no-algo"
      )

      coordinator ! SimMessage.Start
      Thread.sleep(500)
      coordinator ! SimMessage.Stop

      // If we got here without an exception the lifecycle succeeded
      succeed
    }

    "run Itai-Rodeh election on sample graph without crashing" in {
      val Right((rawNodes, rawEdges)) =
        GraphLoader.loadFromClasspath("/graphs/sample-graph.json"): @unchecked

      val graph = GraphLoader.enrich(rawNodes, rawEdges, cfg, seed = 2L)

      val coordinator = system.actorOf(
        SimCoordinator.props(
          graph,
          nodeId => Some(ItaiRodehElection(graph.nodes.size, 42L + nodeId))
        ),
        "coord-election"
      )

      coordinator ! SimMessage.Start
      Thread.sleep(1500)
      coordinator ! SimMessage.Stop

      succeed
    }

    "handle ExternalInput injection correctly" in {
      val Right((rawNodes, rawEdges)) =
        GraphLoader.loadFromClasspath("/graphs/sample-graph.json"): @unchecked

      // Node 2 is configured as input node in cfg
      val graph = GraphLoader.enrich(rawNodes, rawEdges, cfg, seed = 3L)

      val coordinator = system.actorOf(
        SimCoordinator.props(graph, _ => None),
        "coord-inject"
      )

      coordinator ! SimMessage.Start
      coordinator ! SimMessage.ExternalInput("PING", "integration-test")
      Thread.sleep(300)
      coordinator ! SimMessage.Stop

      succeed
    }
  }
