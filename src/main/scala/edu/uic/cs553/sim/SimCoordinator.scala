package edu.uic.cs553.sim

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import edu.uic.cs553.algorithms.DistributedAlgorithm
import edu.uic.cs553.graph.SimGraph

import scala.concurrent.duration.*

object SimCoordinator:
  def props(graph: SimGraph, algoFactory: Int => Option[DistributedAlgorithm]): Props =
    Props(new SimCoordinator(graph, algoFactory))

/**
 * SimCoordinator manages the full lifecycle of a simulation run.
 *
 * Startup sequence:
 *  1. On construction: creates one NodeActor per graph node.
 *  2. Sends Init to every node with its neighbours, edge labels, PDF,
 *     timer config, and ring metadata (ring-next id, ring size).
 *  3. On Start: broadcasts Start to all nodes, triggering traffic generation
 *     and algorithm initiation.
 *  4. On Stop: broadcasts Stop to all nodes, then requests MetricsReports.
 *  5. On ExternalInput: routes to a randomly chosen input node.
 *
 * The algoFactory lambda returns an Optional algorithm per node ID,
 * decoupling the coordinator from any specific algorithm type.
 */
class SimCoordinator(graph: SimGraph, algoFactory: Int => Option[DistributedAlgorithm])
    extends Actor with ActorLogging:

  import SimMessage.*

  // One actor per graph node
  private val nodeRefs: Map[Int, ActorRef] =
    graph.nodes.map { n =>
      n.id -> context.actorOf(NodeActor.props(n.id, algoFactory(n.id)), s"node-${n.id}")
    }.toMap

  // Send Init to every node immediately after creation
  graph.nodes.foreach { node =>
    val nbrs: Map[Int, ActorRef] =
      graph.neighborIds(node.id).flatMap(nid => nodeRefs.get(nid).map(nid -> _)).toMap

    val allowed: Map[Int, Set[String]] =
      graph.outEdges(node.id).map(e => e.toId -> e.allowedTypes).toMap

    nodeRefs(node.id) ! Init(
      nodeId          = node.id,
      neighbors       = nbrs,
      allowedOnEdge   = allowed,
      pdf             = node.pdf,
      timerEnabled    = node.timerEnabled,
      tickEveryMs     = node.tickEveryMs,
      isInputNode     = node.isInputNode,
      rightNeighborId  = graph.ringNextOf.get(node.id),
      ringSize         = graph.nodes.size,
      ringSuccessorRef = graph.ringNextOf.get(node.id).flatMap(nodeRefs.get)
    )
  }

  private val rng = new scala.util.Random(graph.seed)

  override def receive: Receive =

    case Start =>
      log.info(s"SimCoordinator: starting ${nodeRefs.size} nodes")
      nodeRefs.values.foreach(_ ! Start)

    case Stop =>
      log.info("SimCoordinator: stopping all nodes and collecting metrics")
      nodeRefs.values.foreach(_ ! Stop)
      // Create MetricsCollector here so its 5s fallback timeout starts now,
      // not at coordinator construction time (which would fire before Stop).
      val metricsCollector = context.actorOf(MetricsCollector.props(nodeRefs.size), "metrics")
      // GetMetrics is queued after Stop in each node's mailbox — ordering guaranteed
      nodeRefs.values.foreach(_ ! GetMetrics(metricsCollector))

    case ExternalInput(kind, payload) =>
      val inputNodes = graph.nodes.filter(_.isInputNode)
      if inputNodes.nonEmpty then
        val target = inputNodes(rng.nextInt(inputNodes.size))
        nodeRefs.get(target.id).foreach(_ ! ExternalInput(kind, payload))
        log.debug(s"SimCoordinator: injected external input kind=$kind into node-${target.id}")
      else
        log.warning("SimCoordinator: ExternalInput received but no input nodes are configured")
