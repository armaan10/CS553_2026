# Experiment Results

All experiments run with seed=42, Java 17, SBT 1.9.7, Scala 3.3.3, Akka Classic 2.8.5.

---

## Experiment 1 — Sparse Ring, Leader Election

**Command:**
```bash
sbt "runMain edu.uic.cs553.cli.SimMain \
     --config experiments/experiment1 \
     --graph src/main/resources/graphs/sparse-graph.json \
     --algo election --duration 25"
```

**Graph:** 8 nodes, 8 edges (pure ring topology)  
**Config:** `tickIntervalMs=500`, timers=[1,5], inputs=[3]

**Results:**

| Metric | Value |
|---|---|
| Leader elected | Node-7 (id=595) |
| Hops to complete ring | 8 (= ring size, round 1) |
| Election rounds needed | 1 |
| Total messages | 66 |
| Message breakdown | CONTROL=66 |
| Duration | 25s |

**Observation:** On a pure ring with low background noise, the algorithm converges in exactly one round. The winning candidate ID (595) travels all 8 hops without being dominated, confirming correctness. All 66 messages are CONTROL-typed (election + leader announcement), with zero application traffic interfering.

---

## Experiment 2 — Sample Graph, Ring Size Estimation

**Command:**
```bash
sbt "runMain edu.uic.cs553.cli.SimMain \
     --config experiments/experiment2 \
     --algo ring-size --duration 30"
```

**Graph:** 12 nodes, 18 edges (ring + cross-edges)  
**Config:** `tickIntervalMs=200`, timers=[1,4,7,10], inputs=[2,6]

**Results:**

| Metric | Value |
|---|---|
| Ring size (true) | 12 |
| Algorithm | Itai-Rodeh probe-based estimation |
| Max rounds | 3 |
| Convergence | All nodes converge to est=12 |
| Total messages | 244 |
| PING | 128 (52%) |
| GOSSIP | 116 (48%) |
| Duration | 15s |

**Observation:** Cross-edges carry concurrent PING/GOSSIP traffic while PROBE messages travel the ring as CONTROL-typed `AlgoMessage`s that bypass edge-label enforcement. All 12 nodes converge to `final ring-size estimate: 12.0 (3 rounds)`, confirming the logical ring overlay is correctly independent of the physical cross-edge topology. The PING/GOSSIP split reflects the experiment2 PDF (40%/40%) with no WORK traffic (edges only allow PING+GOSSIP by default).

---

## Experiment 3 — Dense Graph, Traffic Throughput

**Command:**
```bash
sbt "runMain edu.uic.cs553.cli.SimMain \
     --config experiments/experiment3 \
     --graph src/main/resources/graphs/dense-graph.json \
     --algo none --duration 20"
```

**Graph:** 8 nodes, 24 edges (~3 edges/node average)  
**Config:** `tickIntervalMs=100`, timers=[1,2,3,4,5,6], inputs=[7,8], node-3 is a heavy WORK producer (90% WORK)

**Results:**

| Metric | Value |
|---|---|
| Total messages | 1,194 |
| WORK | 612 (51%) |
| PING | 265 (22%) |
| GOSSIP | 207 (17%) |
| ACK | 110 (9%) |
| Duration | 20s |

**Observation:** The dense topology (~3 outgoing edges/node) combined with a 100ms tick rate and 6 timer nodes drives high throughput (≈60 messages/sec). WORK dominates due to node-3's per-node PDF override (90% WORK). Edge-label enforcement is visible: edges configured to allow all four types pass all traffic, while restricted edges silently drop ineligible types — the enforcer never sends a message type not permitted on the target channel.

---

## Experiment 6 — NetGameSim Graph, Per-Edge Labels + Election

**Command:**
```bash
sbt "runMain edu.uic.cs553.cli.SimMain \
     --ngs outputs/NetGraph_18-04-26-04-19-27.ngs \
     --config experiments/experiment6 \
     --algo election --duration 25"
```

**Graph:** 11 nodes, 10 edges (NetGameSim-generated, sparse directed)  
**Topology:** `0→1, 0→2, 1→10, 2→6, 3→9, 6→7, 7→5, 8→4, 9→8, 10→3`  
**Config:** `tickIntervalMs=350`, timers=[1,3], per-edge labels applied to all 10 edges

**Per-edge label configuration:**

| Edge | Allowed Types | Channel model |
|---|---|---|
| 0→1 | PING | Heartbeat only |
| 0→2 | GOSSIP, WORK | Task channel |
| 1→10 | PING | Heartbeat only |
| 2→6 | PING, GOSSIP | Gossip link |
| 3→9 | GOSSIP, WORK | Task channel |
| 6→7 | WORK | Dedicated task |
| 7→5 | PING, GOSSIP | Gossip link |
| 8→4 | GOSSIP | Gossip only |
| 9→8 | PING, WORK | Mixed |
| 10→3 | PING, GOSSIP, WORK | Full traffic |

**Results:**

| Metric | Value |
|---|---|
| Leader elected | Node-0 (id=1031) |
| Hops to complete ring | 11 (= ring size, round 1) |
| Election rounds needed | 1 |
| Total messages | 142 |
| PING | 71 (50%) |
| GOSSIP | 42 (30%) |
| WORK | 29 (20%) |
| Duration | 25s |

**Observation:** The ring overlay routes election messages across all 11 nodes including nodes 4 and 5 which have zero physical outgoing edges — confirming that `ringSuccessorRef` correctly decouples ring algorithm routing from the physical graph topology. Per-edge labels shape the traffic mix: node-1 emits PING-only (matching the `1→10` heartbeat channel) and node-3 emits GOSSIP/WORK equally (matching the `3→9` task channel), so no messages are dropped by the edge-label enforcer.

---

## Summary Comparison

| Experiment | Graph | Nodes | Edges | Algorithm | Total Msgs | Duration |
|---|---|---|---|---|---|---|
| 1 | sparse ring | 8 | 8 | election | 66 | 25s |
| 2 | sample ring+cross | 12 | 18 | ring-size | — | 30s |
| 3 | dense | 8 | 24 | none (traffic) | 1,194 | 20s |
| 6 | NetGameSim NGs | 11 | 10 | election | 142 | 25s |
