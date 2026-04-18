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
3. The file `outputs/NetGraph_<timestamp>.ngs` is created.
4. Load it: `sbt "runMain edu.uic.cs553.cli.SimMain --ngs outputs/NetGraph_<timestamp>.ngs"`

`GraphLoader.loadNetGameSimJson` parses the two-line format (line 1: nodes, line 2: edges) using custom circe decoders that tolerate extra fields, so the full NetGameSim schema is not required.

### 2.2 Edge Labels

Every directed edge receives a set of **allowed message types** using the following priority order:

1. **Config per-edge override** (`sim.edgeLabeling.perEdge`) — highest priority; lets experiments impose constraints on NetGameSim graphs without editing the JSON file.
2. **JSON `allowedTypes` field** — present in bundled sample graphs.
3. **Config default** (`sim.edgeLabeling.default`) — fallback for unlisted edges.

`CONTROL` is always appended automatically so algorithm messages (`AlgoMessage`) can traverse any channel regardless of label.

Enforcement is in `NodeActor.sendToEligibleNeighbour` and `NodeActor.receive` for ExternalInput.

### 2.3 Node PDFs

Each node has a **probability mass function** (PMF) over message types.  The default PMF from `sim.traffic.defaultPdf` is applied to all nodes; per-node overrides (`sim.traffic.perNodePdf`) are applied on top.  PDFs are validated to sum to 1.0 ± 0.01 and normalised if they do not.

Sampling uses the inverse-CDF method in `NodeActor.sampleFromPdf()`.

### 2.4 Ring Overlay

Both algorithms require a ring structure.  `SimGraph.ringNextOf` builds a logical ring by sorting all node IDs numerically and mapping each to its successor (wrapping around).  This overlay is fully independent of the physical edge set — `SimCoordinator` resolves each node's ring successor to an `ActorRef` (`ringSuccessorRef`) at `Init` time and passes it directly to the `NodeActor`.  `sendToNeighbour` falls back to this ref when the ring successor is not a direct graph neighbour, so algorithm messages always reach the next ring node regardless of graph topology.

## 3. Actor Architecture

### 3.1 NodeActor

`NodeActor` is the core actor.  It holds:

| Field | Purpose |
|---|---|
| `neighbors` | ActorRef map keyed by node id (graph edges only) |
| `allowedOnEdge` | Per-neighbour set of permitted message types |
| `pdf` | PMF for background traffic generation |
| `rightNeighborId` | Ring successor id (ring algorithms) |
| `ringSuccessorRef` | ActorRef of ring successor — independent of graph edges |
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

Nodes listed in `sim.initiators.inputs` are designated as `isInputNode = true`.  The `SimCoordinator` routes `ExternalInput` messages to one of these nodes.  The CLI supports `--inject <kind>` to inject a message at startup.

## 5. Distributed Algorithms

Both algorithms implement the `DistributedAlgorithm` trait and are instantiated per node by `SimMain`'s `algoFactory` lambda.  Both are implemented exactly as described in:

> Fokkink, W. *Distributed Algorithms: An Intuitive Approach*, MIT Press.

### 5.1 Itai-Rodeh Leader Election

**Setting**: N anonymous processes on a directed ring.  No process has a pre-assigned unique ID.

**State per process**:
- `status ∈ {Active, Passive, Elected}` — initially Active
- `n` — current round number, initially 1
- `idp` — candidate ID for round n (random)

**Message**: `ELECT(round, id, hops, collision)`

**Protocol**:

```
StartRound (active p):
  idp := random(1 .. ringSize × 100)
  send ELECT(n, idp, 1, false) to successor

On receive ELECT(n2, i, h, collision):
  n2 < n  → discard (stale round)
  n2 > n  → adopt round, go Passive, relay ELECT(n2, i, h+1, collision)
  n2 == n:
    Passive  → relay ELECT(n, i, h+1, collision)
    Active, i > idp  → go Passive, relay
    Active, i < idp  → discard (our id dominates)
    Active, i == idp (own message returning):
      h < ringSize  → relay with collision=true
      h == ringSize, collision     → n++, go Active, StartRound
      h == ringSize, !collision    → go Elected, send LEADER(idp)
```

**Correctness**: A message can only complete a full ring lap if no other active node eliminated it (higher id wins).  The collision flag detects when two active nodes sent the same id in the same round.  Incrementing the round number prevents stale messages from interfering with the new round.

**Run**: `sbt "runMain edu.uic.cs553.cli.SimMain --algo election --duration 20"`

### 5.2 Itai-Rodeh Ring Size Estimation

**Setting**: N anonymous processes on a ring, each independently estimating N.

**State per process**:
- `est` — current size estimate, initially 2
- `id` — probe ID for the current round (random)

**Message**: `PROBE(estm, id, h)`

**Protocol**:

```
Initially:
  id := random(1 .. 100000)
  send PROBE(est, id, 1) to successor

On receive PROBE(estm, idm, h):
  estm < est  → ignore (stale)
  estm > est  →
    est := estm
    h < est   → relay PROBE(est, idm, h+1)
    h >= est  → est := est+1; new round
  estm == est →
    h < est   → relay PROBE(est, idm, h+1)
    h >= est  →
      idm != id → est := est+1; new round
      idm == id → output est  (ring size found)
```

**Termination**: The algorithm terminates when a node's own probe completes exactly `est` hops with no id collision — meaning `est` equals the true ring size.  There is no fixed round limit; convergence is guaranteed probabilistically (the ID space is large relative to ring size).

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

`AlgoMessage.data` uses `Map[String, String]`.  This keeps the message open for extension — new algorithm message subtypes only require new `kind` strings and documented `data` keys, without changes to the sealed hierarchy.

### Election message fields

| Message | Fields |
|---|---|
| `ELECT` | `round`, `id`, `hops`, `collision` |
| `LEADER` | `leaderId` |

### Ring-size message fields

| Message | Fields |
|---|---|
| `PROBE` | `estm`, `id`, `h` |

## 7. Experiment Configurations

| Experiment | Graph | Algorithm | Key observation |
|---|---|---|---|
| experiment1 | sparse (8 nodes, ring only) | election | Pure-ring correctness; low background noise |
| experiment2 | sample (12 nodes, ring+cross) | ring-size | Algorithm isolation from cross-edge traffic |
| experiment3 | dense (8 nodes, high connectivity) | none | Max-throughput traffic; edge-label enforcement effect |
| experiment6 | NetGameSim NGs (11 nodes) | election | Per-edge labels + per-node PDFs on a real generated graph |

## 8. Metrics

The following metrics are captured and logged by MetricsCollector at run end:

- **Message counts by type** — how many Envelope messages of each kind were processed across all nodes.
- **In-flight approximation per channel** — cumulative outbound count per (sender, receiver) pair, approximating channel load.
- **Wall-clock duration** — elapsed time from MetricsCollector creation to summary.

## 9. Logging

Logging uses SLF4J → Logback via the `akka-slf4j` bridge.  Key levels:

| Logger | Default level | Shows |
|---|---|---|
| `edu.uic.cs553` | INFO | Algorithm events, node init/stop, metrics summary |
| `akka` | WARN | Akka system warnings only |

To see per-message traffic (send/receive on every edge), **both** of the following must be set to DEBUG:

1. `akka.loglevel` in `src/main/resources/application.conf` — Akka filters log events before they reach SLF4J, so logback alone is not enough.
2. The `edu.uic.cs553` logger level in `src/main/resources/logback.xml`.

## 10. Reproducibility

All random decisions (PDF sampling, probe IDs, election IDs) accept an explicit `seed` parameter.  The seed is configurable via `--seed <long>` on the CLI (default: 42).  Re-running with the same seed, graph, and algorithm produces identical message sequences.
