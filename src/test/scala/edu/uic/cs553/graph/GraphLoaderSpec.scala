package edu.uic.cs553.graph

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class GraphLoaderSpec extends AnyWordSpec with Matchers:

  private val testConfig = ConfigFactory.parseString("""
    sim.messages.types        = ["CONTROL","PING","GOSSIP"]
    sim.edgeLabeling.default  = ["PING","GOSSIP"]
    sim.traffic.tickIntervalMs = 200
    sim.traffic.defaultPdf    = [
      { msg = "PING",   p = 0.6 }
      { msg = "GOSSIP", p = 0.4 }
    ]
    sim.initiators.timers = [1]
    sim.initiators.inputs = [2]
  """)

  "GraphLoader.loadFromClasspath" should {

    "load the bundled sample graph" in {
      GraphLoader.loadFromClasspath("/graphs/sample-graph.json") match
        case Right((nodes, edges)) =>
          nodes should not be empty
          edges should not be empty
          nodes.map(_.id).toSet should contain(1)
        case Left(err) =>
          fail(s"Expected successful load but got: $err")
    }

    "return Left for a missing resource" in {
      GraphLoader.loadFromClasspath("/graphs/does-not-exist.json") shouldBe a[Left[?, ?]]
    }
  }

  "GraphLoader.enrich" should {

    "produce a SimGraph with correct node count" in {
      val rawNodes = List(
        GraphLoader.RawNode(1, 0.5),
        GraphLoader.RawNode(2, 0.7),
        GraphLoader.RawNode(3, 0.3)
      )
      val rawEdges = List(
        GraphLoader.RawEdge(1, 2, 1.0),
        GraphLoader.RawEdge(2, 3, 1.0),
        GraphLoader.RawEdge(3, 1, 1.0)
      )
      val graph = GraphLoader.enrich(rawNodes, rawEdges, testConfig, seed = 42L)

      graph.nodes.size shouldBe 3
      graph.edges.size shouldBe 3
      graph.seed       shouldBe 42L
    }

    "add CONTROL to every edge's allowed types" in {
      val rawNodes = List(GraphLoader.RawNode(1, 0.1), GraphLoader.RawNode(2, 0.2))
      val rawEdges = List(GraphLoader.RawEdge(1, 2, 1.0))
      val graph    = GraphLoader.enrich(rawNodes, rawEdges, testConfig, seed = 0L)

      graph.edges.head.allowedTypes should contain("CONTROL")
    }

    "normalise a PDF that does not sum to 1.0" in {
      val badConfig = ConfigFactory.parseString("""
        sim.messages.types        = ["CONTROL","PING","GOSSIP"]
        sim.edgeLabeling.default  = ["PING"]
        sim.traffic.tickIntervalMs = 100
        sim.traffic.defaultPdf    = [
          { msg = "PING",   p = 0.3 }
          { msg = "GOSSIP", p = 0.3 }
        ]
        sim.initiators.timers = []
        sim.initiators.inputs = []
      """)
      val graph = GraphLoader.enrich(
        List(GraphLoader.RawNode(1, 0.0)),
        Nil,
        badConfig,
        seed = 0L
      )
      val pdfSum = graph.nodes.head.pdf.values.sum
      pdfSum shouldBe (1.0 +- 0.01)
    }

    "build correct ring ordering" in {
      val rawNodes = List(
        GraphLoader.RawNode(3, 0.0),
        GraphLoader.RawNode(1, 0.0),
        GraphLoader.RawNode(2, 0.0)
      )
      val graph = GraphLoader.enrich(rawNodes, Nil, testConfig, seed = 0L)

      graph.ringOrder.map(_.id) shouldBe List(1, 2, 3)
      graph.ringNextOf(1) shouldBe 2
      graph.ringNextOf(2) shouldBe 3
      graph.ringNextOf(3) shouldBe 1  // wraps around
    }
  }
