package edu.uic.cs553.sim

import akka.actor.ActorRef

/**
 * SimMessage is the sealed message ADT for the entire simulation.
 *
 * Using a sealed trait with final case classes guarantees:
 *  - Exhaustive match warnings from the compiler.
 *  - No stringly-typed dispatch; every message kind is a distinct type.
 *  - Serialisability for potential future clustering (all extend Serializable).
 *
 * Message categories:
 *  Lifecycle    — Init, Start, Stop, GetMetrics / MetricsReport
 *  App traffic  — Envelope (node-to-node), ExternalInput (driver injection)
 *  Algorithm    — AlgoMessage (tagged with algorithm name + kind + data map)
 *  Internal     — Tick (private timer trigger, never sent between actors)
 */
sealed trait SimMessage extends Serializable

object SimMessage:

  // ---- Lifecycle ----

  /**
   * Sent by SimCoordinator once per node after actor creation.
   *
   * allowedOnEdge: neighborId -> set of message types permitted on that channel.
   * This is the per-node view of global edge labels; the actor uses it to enforce
   * that application traffic respects the topology's channel constraints.
   *
   * rightNeighborId / ringSize: used exclusively by ring algorithms.
   */
  final case class Init(
    nodeId:           Int,
    neighbors:        Map[Int, ActorRef],
    allowedOnEdge:    Map[Int, Set[String]],  // neighborId -> allowed msg types
    pdf:              Map[String, Double],     // msgType -> emission probability
    timerEnabled:     Boolean,
    tickEveryMs:      Long,
    isInputNode:      Boolean,
    rightNeighborId:  Option[Int],
    ringSize:         Int,
    ringSuccessorRef: Option[ActorRef] = None // ActorRef for ring successor, independent of graph edges
  ) extends SimMessage

  case object Start extends SimMessage
  case object Stop  extends SimMessage

  final case class GetMetrics(replyTo: ActorRef) extends SimMessage

  final case class MetricsReport(
    nodeId:          Int,
    msgCountsByType: Map[String, Long],
    inFlightApprox:  Map[Int, Long]     // neighborId -> approx in-flight count
  ) extends SimMessage

  // ---- Application traffic ----

  /**
   * Normal routed message between two nodes.
   * Edge-label enforcement happens at the sender before this is dispatched.
   */
  final case class Envelope(from: Int, kind: String, payload: String) extends SimMessage

  /**
   * External message injected into an input node by the simulation driver.
   * Models a client, sensor, or test harness stimulus.
   */
  final case class ExternalInput(kind: String, payload: String) extends SimMessage

  // ---- Distributed algorithm control ----

  /**
   * Algorithm-specific control message.
   *
   * algoName: identifies which DistributedAlgorithm instance should handle this.
   * kind:     message type within that algorithm (e.g., "ELECT", "LEADER", "PROBE").
   * data:     typed payload as String map — avoids Any while keeping the protocol
   *           open for extension without changing the sealed hierarchy.
   */
  final case class AlgoMessage(
    algoName: String,
    kind:     String,
    data:     Map[String, String]
  ) extends SimMessage

  // ---- Internal ----

  /** Timer tick — drives background traffic generation and algorithm hooks. */
  private[sim] case object Tick extends SimMessage
