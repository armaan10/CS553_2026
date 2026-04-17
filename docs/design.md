# CS553 Distributed Algorithms Simulator — Design Document

## 1. Overview

This project implements an end-to-end distributed algorithms simulation framework in Scala 3 using Akka Classic actors.  The pipeline has four stages:

```
NetGameSim (graph generation)
    ↓  JSON export
GraphLoader (enrichment: edge labels, node PDFs)
    ↓  SimGraph
SimCoordinator (lifecycle, actor creation, Init dispatch)
    ↓  Init / Start / Stop
NodeActors (traffic generation, algorithm hosting, metrics)
    ↓  MetricsReport
MetricsCollector (aggregation, summary logging)
```

## 2. Graph Model and Enrichment

### 2.1 NetGameSim Integration

NetGameSim generates random directed graphs and serialises them to disk.  To use JSON output (required for this simulator):

1. Edit `netgamesim/GenericSimUtilities/src/main/resources/application.conf`:
   ```hocon
   NGSimulator.outputDirectory = "/absolute/path/to/CS553_2026/outputs/"
   NGSimulator.OutputGraphRepresentation.contentType = "json"
   ```
2. Run: `cd netgamesim && sbt run`
3. The file `outputs/NetGraph_<timestamp>.ngs.json` is created.
4. Load it: `sbt "runMain edu.uic.cs553.cli.SimMain --ngs outputs/NetGraph_<timestamp>.ngs.json"`

`GraphLoader.loadNetGameSimJson` parses the two-line format (line 1: nodes, line 2: edges) using custom circe decoders that tolerate extra fields, so the full NetGameSim schema is not required.

### 2.2 Edge Labels

Every directed edge receives a set of **allowed message types** derived from `sim.edgeLabeling.default` in the config.  `CONTROL` is always appended automatically so algorithm control messages (AlgoMessage) can traverse any channel.

Enforcement is in `NodeActor.sendToEligibleNeighbour` and `NodeActor.receive` for ExternalInput: a message is only forwarded to a neighbour whose edge entry contains its type.

### 2.3 Node PDFs

Each node has a **probability mass function** (PMF) over message types.  The default PMF from `sim.traffic.defaultPdf` is applied to all nodes; per-node overrides are read from `sim.traffic.perNodePdf`.  PDFs are validated to sum to 1.0 ± 0.01 and normalised if they do not.

Sampling uses the inverse-CDF method in `NodeActor.sampleFromPdf()`.

## 3. Actor Architecture

### 3.1 NodeActor

`NodeActor` is the core actor.  It holds:

| Field | Purpose |
|---|---|
| `neighbors` | ActorRef map keyed by node id |
| `allowedOnEdge` | Per-neighbour set of permitted message types |
| `pdf` | PMF for background traffic generation |
| `rightNeighborId` | Ring successor id (ring algorithms) |
| `ringSize` | Total nodes in ring (ring algorithms) |
| `algorithm` | Optional `DistributedAlgorithm` plug-in |

**Message handling order:**
1. Lifecycle (Init, Start, Stop)
2. Background traffic (Tick, ExternalInput, Envelope)
3. Algorithm control (AlgoMessage)
4. Metrics (GetMetrics)

**Why vars**: Akka guarantees at most one thread processes an actor's `receive` at a time.  Var-based local state is the idiomatic Akka Classic pattern and avoids the allocation overhead of rebuilding immutable state on every tick.

### 3.2 SimCoordinator

`SimCoordinator` manages the run lifecycle:
- Creates all `NodeActor` instances at construction time.
- Sends `Init` immediately with full neighbour map, edge labels, PDF, timer config, and ring metadata.
- Broadcasts `Start` on request.
- On `Stop`: broadcasts Stop, then sends `GetMetrics` to all nodes (ordering within each actor's mailbox guarantees Stop is processed first).
- Routes `ExternalInput` to a randomly chosen input node.

### 3.3 MetricsCollector

Waits for `MetricsReport` from every node (or a 5-second timeout), then logs:
- Total message count per type
- Per-channel approximate in-flight count (top 10)
- Wall-clock run duration

## 4. Computation Initiation

Two mechanisms are supported, per the spec:

### 4.1 Timer Nodes

Nodes listed in `sim.initiators.timers` receive `timerEnabled = true` in their Init.  They start an Akka `Timers` recurring timer at `tickEveryMs` that fires `Tick`, which samples the node's PDF and sends an `Envelope` to an eligible neighbour.

### 4.2 Input Nodes

Nodes listed in `sim.initiators.inputs` are designated as `isInputNode = true`.  The `SimCoordinator` routes `ExternalInput` messages to one of these nodes.  The CLI supports `--inject <kind>` to inject a message at startup.  In interactive mode, the user can send additional `ExternalInput` messages programmatically.

## 5. Distributed Algorithms

Both algorithms implement the `DistributedAlgorithm` trait and are instantiated per node by `SimMain`'s `algoFactory` lambda.

### 5.1 Itai-Rodeh Leader Election

**Paper**: Itai & Rodeh, "Symmetry Breaking in Distributed Networks", SIAM J. Comput. 1990.

**Setting**: N anonymous processes on a directed ring.  No process has a pre-assigned unique ID.

**Protocol** (single instance per node):

1. `onStart`: Pick random `myId ∈ [1, ringSize × 100]`.  Send `ELECT(id=myId, hops=1)` to right ring successor.
2. On `ELECT(id, hops)`:
   - `id > myId` → forward `ELECT(id, hops+1)`.
   - `id < myId` → discard.
   - `id == myId && hops < ringSize` → collision; pick new `myId`, restart.
   - `id == myId && hops == ringSize` → circuit complete; declare self leader, send `LEADER(leaderId=myId)`.
3. On `LEADER(leaderId)`:
   - `leaderId ≠ myId` → forward `LEADER` (propagate announcement).
   - `leaderId == myId` → announcement returned to originator; log completion.

**Correctness**: The highest active ID circulates the ring undefeated with probability → 1.  Collisions cause restarts with fresh random IDs; since |ID space| >> ringSize, restarts are rare.

**Run**: `sbt "runMain edu.uic.cs553.cli.SimMain --algo election --duration 20"`

### 5.2 Itai-Rodeh Ring Size Estimation

**Setting**: N anonymous processes on a ring, each needing to estimate N without prior knowledge.

**Protocol** (single instance per node, up to `maxRounds` rounds):

1. `onStart`: Pick random `probeId ∈ [1, 100000]`.  Send `PROBE(probeId, hops=1)` to right successor.
2. On `PROBE(probeId, hops)`:
   - `probeId == myProbeId` → probe returned; estimate = `hops`.  Start next round if `roundsCompleted < maxRounds`.
   - `probeId ≠ myProbeId` → relay `PROBE(probeId, hops+1)`.

**Accuracy**: With `probeIdRange = 100,000 >> ringSize`, collision probability per round is < `ringSize / 100000`.  Running 3 rounds gives a running average that is almost always exactly the ring size.

**Run**: `sbt "runMain edu.uic.cs553.cli.SimMain --algo ring-size --duration 30"`

## 6. Message Protocol

```
SimMessage (sealed trait)
├── Init           — node configuration (lifecycle)
├── Start          — begin traffic and algorithms
├── Stop           — halt traffic and timers
├── GetMetrics     — request metrics report
├── MetricsReport  — metrics data from one node
├── Envelope       — application traffic (subject to edge-label enforcement)
├── ExternalInput  — driver-injected stimulus
├── AlgoMessage    — algorithm control (CONTROL type, bypasses edge-label check)
└── Tick           — internal timer trigger (private[sim])
```

`AlgoMessage.data` uses `Map[String, String]` rather than `Any`.  This avoids stringly-typed dispatch while keeping the message open for extension — new algorithm message subtypes only require adding new `kind` strings and documented `data` keys, not changes to the sealed hierarchy.

## 7. Experiment Configurations

| Experiment | Graph | Algorithm | Key observation |
|---|---|---|---|
| experiment1 | sparse (8 nodes, ring only) | election | Pure-ring correctness; low background noise |
| experiment2 | sample (12 nodes, ring+cross) | ring-size | Algorithm isolation from cross-edge traffic |
| experiment3 | dense (8 nodes, high connectivity) | none | Max-throughput traffic; edge-label enforcement effect |

## 8. Metrics

The following metrics are captured and logged by MetricsCollector at run end:

- **Message counts by type** — how many Envelope messages of each kind were processed across all nodes.
- **In-flight approximation per channel** — cumulative outbound count per (sender, receiver) pair, approximating channel load.
- **Wall-clock duration** — elapsed time from MetricsCollector creation to summary.

Note on Cinnamon: Lightbend Cinnamon (Telemetry) requires a commercial Lightbend subscription and is therefore not included. The manual metrics above cover all required data points from the course spec (message counts by type, in-flight messages per channel, time to completion).

## 9. Reproducibility

All random decisions (PDF sampling, probe IDs, random IDs) accept an explicit `seed` parameter.  The seed is configurable via `--seed <long>` on the CLI.  The default seed is 42.  Re-running with the same seed, graph, and algorithm produces identical message sequences.
