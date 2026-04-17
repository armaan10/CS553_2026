package edu.uic.cs553.graph

/**
 * SimGraph is the enriched graph model produced by GraphLoader.
 *
 * Enrichment adds two layers of metadata on top of the raw NetGameSim topology:
 *   - Node PDFs: each node has a probability mass function over message types,
 *     controlling what traffic it autonomously generates.
 *   - Edge labels: each directed edge carries a set of allowed message types;
 *     the NodeActor enforces these at send time so forbidden types cannot traverse
 *     a channel (mirrors the real-world channel capacity / protocol constraint).
 *
 * Ring ordering: nodes sorted by id form a deterministic logical ring used by
 * ring-topology algorithms (Itai-Rodeh) without requiring the underlying graph
 * to be a ring physically.
 */
case class SimNode(
  id:           Int,
  storedValue:  Double,
  pdf:          Map[String, Double],  // msgType -> emission probability
  timerEnabled: Boolean,
  tickEveryMs:  Long,
  isInputNode:  Boolean
)

case class SimEdge(
  fromId:       Int,
  toId:         Int,
  cost:         Double,
  allowedTypes: Set[String]           // edge label: message types allowed on this channel
)

case class SimGraph(
  nodes: List[SimNode],
  edges: List[SimEdge],
  seed:  Long
):
  val nodeIds: Set[Int] = nodes.map(_.id).toSet

  /** Out-edges per source node id. */
  val outEdges: Map[Int, List[SimEdge]] =
    edges.groupBy(_.fromId).withDefaultValue(Nil)

  /** Set of neighbor ids reachable from each node. */
  val neighborIds: Map[Int, Set[Int]] =
    outEdges.view.mapValues(_.map(_.toId).toSet).toMap.withDefaultValue(Set.empty)

  /** Nodes sorted by id — defines the logical ring order for ring algorithms. */
  val ringOrder: List[SimNode] = nodes.sortBy(_.id)

  /**
   * Successor in the logical ring for each node id.
   * The last node wraps around to the first, completing the ring.
   */
  val ringNextOf: Map[Int, Int] =
    if ringOrder.isEmpty then Map.empty
    else
      val ids = ringOrder.map(_.id)
      ids.zipWithIndex.map { (id, i) =>
        id -> ids((i + 1) % ids.size)
      }.toMap
