package edu.uic.cs553.algorithms

import edu.uic.cs553.sim.SimMessage

/**
 * Itai-Rodeh Leader Election for Anonymous Rings.
 *
 * Reference: Itai & Rodeh, "Symmetry Breaking in Distributed Networks" (1990).
 *
 * Problem: elect a unique leader in a ring of N *anonymous* processes — processes
 * have no pre-assigned unique identifiers and cannot distinguish themselves from
 * their neighbours.
 *
 * Algorithm outline (one instance per ring node):
 *
 *  Initiation (onStart):
 *    - Node picks a random ID in [1, idRange] where idRange = ringSize * 100.
 *      A large range makes collisions unlikely in expectation.
 *    - Sends ELECT(id=myId, hops=1) to its right-ring successor.
 *
 *  On receiving ELECT(id, hops):
 *    - id > myId  → forward ELECT(id, hops+1) rightward (higher ID keeps circulating).
 *    - id < myId  → discard (this ID cannot win; the current node's ID dominates it).
 *    - id == myId:
 *        hops < ringSize → ID collision: two nodes picked the same random ID.
 *          Pick a new random myId, restart with ELECT(myId, 1).
 *        hops == ringSize → the message has traversed the full ring undefeated:
 *          this node is the leader. Send LEADER(leaderId=myId) clockwise.
 *
 *  On receiving LEADER(leaderId):
 *    - If leaderId != myId: forward LEADER rightward (propagate announcement).
 *    - If leaderId == myId: the LEADER message has returned to originator —
 *      election is fully complete; log and stop forwarding.
 *
 * Correctness: with probability → 1 over rounds, exactly one node's ID survives
 * a full ring traversal. Ties are re-resolved by choosing new random IDs.
 *
 * Mutable state: per-node, accessed only from the actor thread (safe under Akka).
 *
 * @param configRingSize expected ring size (used to set idRange and detect full circuit)
 * @param seed           per-node random seed for deterministic re-runs
 */
class ItaiRodehElection(configRingSize: Int, seed: Long) extends DistributedAlgorithm:

  // Per-node state — actor-local, single-threaded access guaranteed by Akka
  private var myId:            Int                 = 0
  private var phase:           ElectionPhase       = ElectionPhase.Idle
  private var rng:             scala.util.Random   = new scala.util.Random(seed)
  private var rightNeighborId: Int                 = -1
  private var effectiveRingSize: Int               = configRingSize.max(1)

  private enum ElectionPhase:
    case Idle, Candidate, Elected

  override def name: String = ItaiRodehElection.Name

  override def onStart(ctx: NodeContext): Unit =
    rightNeighborId   = ctx.rightNeighborId.getOrElse(-1)
    effectiveRingSize = ctx.ringSize.max(1)
    if rightNeighborId < 0 then
      ctx.log.warning(s"[${name}][Node-${ctx.nodeId}] no rightNeighbour — cannot participate in ring election")
    else
      myId  = pickRandomId()
      phase = ElectionPhase.Candidate
      ctx.log.info(s"[${name}][Node-${ctx.nodeId}] election started: myId=$myId ringSize=$effectiveRingSize")
      sendElect(ctx, myId, 1)

  override def onAlgoMessage(ctx: NodeContext, msg: SimMessage.AlgoMessage): Unit =
    msg.kind match
      case "ELECT"  => handleElect(ctx, msg.data("id").toInt, msg.data("hops").toInt)
      case "LEADER" => handleLeader(ctx, msg.data("leaderId").toInt)
      case other    => ctx.log.warning(s"[${name}][Node-${ctx.nodeId}] unknown kind: $other")

  // ------- private message handlers -------

  private def handleElect(ctx: NodeContext, inId: Int, hops: Int): Unit =
    if inId > myId then
      ctx.log.debug(s"[${name}][Node-${ctx.nodeId}] forwarding ELECT($inId, ${hops+1})")
      sendElect(ctx, inId, hops + 1)
    else if inId < myId then
      ctx.log.debug(s"[${name}][Node-${ctx.nodeId}] discarding ELECT($inId) — dominated by myId=$myId")
    else
      // inId == myId
      if hops < effectiveRingSize then
        // Collision: another node chose the same ID
        val newId = pickRandomId()
        ctx.log.info(s"[${name}][Node-${ctx.nodeId}] ID collision at hops=$hops; restarting with newId=$newId")
        myId = newId
        sendElect(ctx, myId, 1)
      else
        // hops == ringSize: circuit complete — this node is the leader
        phase = ElectionPhase.Elected
        ctx.log.info(s"[${name}][Node-${ctx.nodeId}] ELECTED as leader (id=$myId, hops=$hops)")
        ctx.send(rightNeighborId, SimMessage.AlgoMessage(name, "LEADER", Map("leaderId" -> myId.toString)))

  private def handleLeader(ctx: NodeContext, leaderId: Int): Unit =
    if leaderId == myId then
      ctx.log.info(s"[${name}][Node-${ctx.nodeId}] LEADER announcement has returned to originator — election complete")
    else
      ctx.log.info(s"[${name}][Node-${ctx.nodeId}] acknowledging leader=$leaderId, forwarding announcement")
      ctx.send(rightNeighborId, SimMessage.AlgoMessage(name, "LEADER", Map("leaderId" -> leaderId.toString)))

  // ------- helpers -------

  private def pickRandomId(): Int =
    rng.nextInt(effectiveRingSize * 100) + 1

  private def sendElect(ctx: NodeContext, id: Int, hops: Int): Unit =
    ctx.send(rightNeighborId, SimMessage.AlgoMessage(name, "ELECT",
      Map("id" -> id.toString, "hops" -> hops.toString)))

object ItaiRodehElection:
  val Name: String = "itai-rodeh-election"
