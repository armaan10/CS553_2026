package edu.uic.cs553.algorithms

import edu.uic.cs553.sim.SimMessage

/**
 * Itai-Rodeh Ring Size Estimation for Anonymous Rings.
 *
 * Reference: Itai & Rodeh, "Symmetry Breaking in Distributed Networks" (1990).
 *
 * Problem: estimate the size of a ring of N anonymous processes without any
 * process knowing N in advance.
 *
 * Algorithm outline (one instance per ring node):
 *
 *  Initiation (onStart):
 *    - Node picks a random probe ID in [1, probeIdRange].
 *    - Sends PROBE(probeId=myProbeId, hops=1) to its right-ring successor.
 *
 *  On receiving PROBE(probeId, hops):
 *    - If probeId == myProbeId:
 *        The probe has returned to its originator.
 *        Estimated ring size = hops.
 *        Log the result; optionally run another round for averaging.
 *    - If probeId != myProbeId:
 *        Forward PROBE(probeId, hops+1) to the right (relay foreign probes).
 *
 * Accuracy: a single round gives the exact ring size only if no two nodes share
 * a probeId.  With probeIdRange >> ringSize the collision probability is low.
 * Running multiple rounds and averaging improves accuracy; this implementation
 * supports a configurable number of rounds via maxRounds.
 *
 * Why this is distinct from the election algorithm: ring size estimation can
 * run concurrently with the election — it does not require a leader first.
 * Estimated sizes from all nodes can be aggregated by the MetricsCollector.
 *
 * Mutable state: per-node, actor-thread–safe under Akka.
 *
 * @param seed      per-node random seed for reproducibility
 * @param maxRounds number of probe rounds before stopping (default 3)
 */
class ItaiRodehRingSize(seed: Long, maxRounds: Int = 3) extends DistributedAlgorithm:

  // Per-node state — actor-local, single-threaded
  private var myProbeId:         Int               = 0
  private var rightNeighborId:   Int               = -1
  private var probeActive:       Boolean           = false
  private var roundsCompleted:   Int               = 0
  private var sizeEstimates:     List[Int]         = Nil   // one estimate per completed round
  private var rng:               scala.util.Random = new scala.util.Random(seed)

  override def name: String = ItaiRodehRingSize.Name

  override def onStart(ctx: NodeContext): Unit =
    rightNeighborId = ctx.rightNeighborId.getOrElse(-1)
    if rightNeighborId < 0 then
      ctx.log.warning(s"[${name}][Node-${ctx.nodeId}] no rightNeighbour — cannot probe ring size")
    else
      ctx.log.info(s"[${name}][Node-${ctx.nodeId}] starting ring-size probe (maxRounds=$maxRounds)")
      startProbe(ctx)

  override def onAlgoMessage(ctx: NodeContext, msg: SimMessage.AlgoMessage): Unit =
    msg.kind match
      case "PROBE" => handleProbe(ctx, msg.data("probeId").toInt, msg.data("hops").toInt)
      case other   => ctx.log.warning(s"[${name}][Node-${ctx.nodeId}] unknown kind: $other")

  // ------- private handlers -------

  private def handleProbe(ctx: NodeContext, probeId: Int, hops: Int): Unit =
    if probeId == myProbeId && probeActive then
      // Our own probe returned: hops == ring size for this round
      val estimate = hops
      sizeEstimates = estimate :: sizeEstimates
      roundsCompleted += 1
      probeActive = false
      val avg = sizeEstimates.sum.toDouble / sizeEstimates.size
      ctx.log.info(
        s"[${name}][Node-${ctx.nodeId}] round $roundsCompleted complete: " +
        s"estimate=$estimate, running average=${f"$avg%.1f"} over ${sizeEstimates.size} rounds"
      )
      if roundsCompleted < maxRounds then
        startProbe(ctx)   // start next round for better accuracy
      else
        ctx.log.info(s"[${name}][Node-${ctx.nodeId}] final ring-size estimate: ${f"$avg%.1f"} (${sizeEstimates.size} rounds)")
    else
      // Foreign probe: relay it forward unchanged
      ctx.log.debug(s"[${name}][Node-${ctx.nodeId}] relaying foreign probe $probeId hops=${hops+1}")
      ctx.send(rightNeighborId, SimMessage.AlgoMessage(name, "PROBE",
        Map("probeId" -> probeId.toString, "hops" -> (hops + 1).toString)))

  private def startProbe(ctx: NodeContext): Unit =
    myProbeId   = rng.nextInt(ItaiRodehRingSize.ProbeIdRange) + 1
    probeActive = true
    ctx.log.debug(s"[${name}][Node-${ctx.nodeId}] sending PROBE probeId=$myProbeId round=${roundsCompleted+1}")
    ctx.send(rightNeighborId, SimMessage.AlgoMessage(name, "PROBE",
      Map("probeId" -> myProbeId.toString, "hops" -> "1")))

  /** Returns the running average ring-size estimate, or 0 if no rounds completed. */
  def averageEstimate: Double =
    if sizeEstimates.isEmpty then 0.0
    else sizeEstimates.sum.toDouble / sizeEstimates.size

object ItaiRodehRingSize:
  val Name:         String = "itai-rodeh-ring-size"
  val ProbeIdRange: Int    = 100_000  // large range keeps collision probability low
