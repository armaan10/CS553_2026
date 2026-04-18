package edu.uic.cs553.algorithms

import akka.actor.ActorRef
import akka.event.LoggingAdapter
import edu.uic.cs553.sim.SimMessage

/**
 * NodeContext packages everything a DistributedAlgorithm needs to participate
 * in a simulation round, without exposing the actor internals.
 *
 * - send(neighbourId, msg): routes a SimMessage to the given neighbour via the
 *   actor's sendToNeighbour path (bypasses edge-label check — algorithm control
 *   messages use CONTROL type which is always permitted).
 * - rightNeighborId / ringSize: ring-topology metadata for ring algorithms.
 *   rightNeighborId is None if the node has not been assigned a ring position.
 */
case class NodeContext(
  nodeId:          Int,
  neighbors:       Map[Int, ActorRef],
  rightNeighborId: Option[Int],
  ringSize:        Int,
  send:            (Int, SimMessage) => Unit,
  log:             LoggingAdapter
)

/**
 * DistributedAlgorithm is the plug-in interface for distributed algorithms.
 *
 * Each NodeActor that participates in an algorithm holds one instance of the
 * chosen implementation.  Callbacks are always invoked from the actor thread,
 * so implementations may safely hold mutable state without synchronisation.
 *
 * Default implementations are no-ops so algorithms only override what they need.
 */
trait DistributedAlgorithm:

  /** Identifies this algorithm; must match the algoName field of AlgoMessages. */
  def name: String

  /** Called once when the node receives Start — algorithm initiates here. */
  def onStart(ctx: NodeContext): Unit = ()

  /** Called when an AlgoMessage tagged with this algorithm's name arrives. */
  def onAlgoMessage(ctx: NodeContext, msg: SimMessage.AlgoMessage): Unit = ()

  /** Called on every background traffic tick — algorithms can piggyback. */
  def onTick(ctx: NodeContext): Unit = ()

  /**
   * Called when a normal application-traffic Envelope arrives.
   * Algorithms may observe traffic (e.g., for snapshot algorithms) without
   * modifying the Envelope.
   */
  def onEnvelope(ctx: NodeContext, env: SimMessage.Envelope): Unit = ()
