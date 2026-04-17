package edu.uic.cs553.sim

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Timers}
import edu.uic.cs553.algorithms.{DistributedAlgorithm, NodeContext}

import scala.concurrent.duration.*

object NodeActor:
  def props(nodeId: Int, algorithm: Option[DistributedAlgorithm]): Props =
    Props(new NodeActor(nodeId, algorithm))

/**
 * NodeActor represents a single node in the distributed simulation.
 *
 * Responsibilities:
 *  1. Enforce edge labels — application traffic (Envelope) is only forwarded
 *     to neighbours whose channel allows that message type.
 *  2. Generate background traffic — a timer fires at tickEveryMs and samples
 *     the node's PDF to produce a random message type, then routes it to an
 *     eligible neighbour. Models realistic background load.
 *  3. Accept external input — the driver can inject messages into input nodes
 *     via ExternalInput without bypassing edge-label rules.
 *  4. Run a distributed algorithm — an optional DistributedAlgorithm plug-in
 *     receives callbacks on Start, each incoming AlgoMessage, and each tick.
 *  5. Collect metrics — message counts per type and approximate in-flight
 *     counts per channel are tracked for the MetricsCollector.
 *
 * Why vars: Akka guarantees that an actor's receive runs on at most one thread
 * at a time, so all state below is effectively single-threaded. Using vars here
 * avoids the overhead of immutable rebuild on every state change and matches
 * the idiomatic Akka Classic pattern for actor-local mutable state.
 */
class NodeActor(nodeId: Int, algorithm: Option[DistributedAlgorithm])
    extends Actor with ActorLogging with Timers:

  import SimMessage.*

  // Actor-local mutable state — safe: Akka single-threaded execution guarantee
  private var neighbors:     Map[Int, ActorRef]       = Map.empty
  private var allowedOnEdge: Map[Int, Set[String]]    = Map.empty  // neighborId -> allowed types
  private var pdf:           Map[String, Double]      = Map.empty
  private var rightNeighbor: Option[Int]              = None
  private var ringSize:      Int                      = 1
  private var rng:           scala.util.Random        = new scala.util.Random()

  // Metrics (accumulated until GetMetrics is received)
  private var msgCountsByType: Map[String, Long] = Map.empty
  private var inFlightApprox:  Map[Int, Long]    = Map.empty

  override def receive: Receive =

    case Init(nid, nbrs, allowed, pdfMap, timerOn, tickMs, _, rightNbr, rSz) =>
      neighbors     = nbrs
      allowedOnEdge = allowed
      pdf           = pdfMap
      rightNeighbor = rightNbr
      ringSize      = rSz
      rng           = new scala.util.Random(rSz.toLong * nid)  // deterministic per-node seed
      if timerOn then
        timers.startTimerAtFixedRate("traffic-tick", Tick, tickMs.millis)
      log.info(s"[Node-$nodeId] initialised: ${neighbors.size} neighbours, " +
               s"timer=$timerOn, rightNeighbour=$rightNbr, ringSize=$rSz")

    case Start =>
      algorithm.foreach(_.onStart(makeContext()))
      log.info(s"[Node-$nodeId] started")

    case Stop =>
      timers.cancelAll()
      log.info(s"[Node-$nodeId] stopped — message counts: $msgCountsByType")

    case Tick =>
      val kind = sampleFromPdf()
      sendToEligibleNeighbour(kind, s"tick-$nodeId")
      algorithm.foreach(_.onTick(makeContext()))

    case ExternalInput(kind, payload) =>
      // Input nodes accept driver-injected messages and route them like normal traffic
      val eligible = neighboursAllowingType(kind).toList
      if eligible.nonEmpty then
        val toId = eligible(rng.nextInt(eligible.size))
        neighbours(toId) ! Envelope(nodeId, kind, payload)
        trackInFlight(toId)
        log.debug(s"[Node-$nodeId] forwarded external input kind=$kind to node-$toId")
      else
        log.warning(s"[Node-$nodeId] no eligible neighbour for external input kind=$kind")

    case env @ Envelope(from, kind, _) =>
      countMsg(kind)
      algorithm.foreach(_.onEnvelope(makeContext(), env))
      log.debug(s"[Node-$nodeId] received Envelope from=$from kind=$kind")

    case msg: AlgoMessage if algorithm.exists(_.name == msg.algoName) =>
      algorithm.foreach(_.onAlgoMessage(makeContext(), msg))

    case msg: AlgoMessage =>
      log.debug(s"[Node-$nodeId] ignoring AlgoMessage for unknown algo ${msg.algoName}")

    case GetMetrics(replyTo) =>
      replyTo ! MetricsReport(nodeId, msgCountsByType, inFlightApprox)

  // ------- private helpers -------

  private def makeContext(): NodeContext =
    NodeContext(
      nodeId          = nodeId,
      neighbors       = neighbors,
      rightNeighborId = rightNeighbor,
      ringSize        = ringSize,
      send            = sendToNeighbour,
      log             = log
    )

  /**
   * Sample a message type from the node's PDF using the inverse CDF method.
   * The PDF is validated at load time to sum to 1.0, so the fallback "PING"
   * is only reached if the PDF is empty (should not happen in practice).
   */
  private def sampleFromPdf(): String =
    val r = rng.nextDouble()
    pdf.foldLeft((0.0, pdf.keys.headOption.getOrElse("PING"))) {
      case ((cum, sel), (kind, prob)) =>
        val next = cum + prob
        if r >= cum && r < next then (next, kind) else (next, sel)
    }._2

  private def sendToEligibleNeighbour(kind: String, payload: String): Unit =
    val eligible = neighboursAllowingType(kind).toList
    if eligible.nonEmpty then
      val toId = eligible(rng.nextInt(eligible.size))
      neighbours(toId) ! Envelope(nodeId, kind, payload)
      trackInFlight(toId)

  private def sendToNeighbour(toId: Int, msg: SimMessage): Unit =
    neighbors.get(toId) match
      case Some(ref) => ref ! msg
      case None      => log.warning(s"[Node-$nodeId] sendToNeighbour: unknown id $toId")

  /** Returns the set of neighbour ids whose edge allows the given message type. */
  private def neighboursAllowingType(kind: String): Set[Int] =
    allowedOnEdge.collect { case (to, types) if types.contains(kind) => to }.toSet

  // Convenient alias so both spellings compile (used internally)
  private def neighbours(id: Int): ActorRef = neighbors(id)

  private def countMsg(kind: String): Unit =
    msgCountsByType = msgCountsByType.updated(kind, msgCountsByType.getOrElse(kind, 0L) + 1)

  private def trackInFlight(toId: Int): Unit =
    inFlightApprox = inFlightApprox.updated(toId, inFlightApprox.getOrElse(toId, 0L) + 1)
