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
| Total messages | 90 |
| CONTROL | 61 (68%) |
| PING | 29 (32%) |
| Duration | 25s |

**Observation:** On a pure ring with low background noise, the algorithm converges in exactly one round. The winning candidate ID (595) travels all 8 hops without being dominated, confirming correctness. CONTROL messages carry the election traffic; PING comes from timer nodes 1 and 5 generating low-rate background traffic on the ring edges (edge default allows PING+GOSSIP; GOSSIP is sampled but is < 500ms tick with low probability in this run). The algorithm is unaffected by concurrent application traffic.

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
     --ngs outputs/NetGraph_18-04-26-05-03-31.ngs \
     --config experiments/experiment6 \
     --algo election --duration 25"
```

**Graph:** 11 nodes, 10 edges (NetGameSim-generated, sparse directed)  
**Topology:** `0→1, 0→2, 1→9, 2→4, 3→8, 5→3, 6→5, 7→10, 9→7, 10→6`  
**Config:** `tickIntervalMs=350`, timers=[0,1,5,9], per-edge labels applied to all 10 edges  
**Note:** Nodes 4 and 8 have no outgoing edges — they participate in the ring as relay nodes only.

**Per-edge label configuration:**

| Edge | Allowed Types | Channel model |
|---|---|---|
| 0→1 | PING | Heartbeat only |
| 0→2 | GOSSIP, WORK | Task channel |
| 1→9 | PING | Heartbeat only |
| 2→4 | PING, GOSSIP | Gossip link |
| 3→8 | GOSSIP, WORK | Task channel |
| 5→3 | GOSSIP | Gossip only |
| 6→5 | WORK | Dedicated task |
| 7→10 | PING, GOSSIP, WORK | Full traffic |
| 9→7 | PING, WORK | Mixed |
| 10→6 | PING, GOSSIP | Gossip link |

**Results:**

| Metric | Value |
|---|---|
| Leader elected | Node-0 (id=1031) |
| Hops to complete ring | 11 (= ring size, round 1) |
| Election rounds needed | 1 |
| Total messages | 284 |
| PING | 135 (47%) |
| GOSSIP | 91 (32%) |
| WORK | 58 (20%) |
| Duration | 25s |

**Observation:** The ring overlay routes election messages across all 11 nodes including nodes 4 and 8 which have zero physical outgoing edges — confirming that `ringSuccessorRef` correctly decouples ring algorithm routing from the physical graph topology. Per-edge labels shape the traffic mix: node-1 emits PING-only (matching the `1→9` heartbeat channel), node-5 emits GOSSIP-only (matching the `5→3` gossip-only channel), and node-9 emits PING+WORK equally (matching the `9→7` mixed channel) — so no messages are dropped by the edge-label enforcer. The WORK fraction (20%) comes entirely from nodes 0 and 9 whose outgoing task channels allow it.

---

## Summary Comparison

| Experiment | Graph | Nodes | Edges | Algorithm | Total Msgs | Duration |
|---|---|---|---|---|---|---|
| 1 | sparse ring | 8 | 8 | election | 90 | 25s |
| 2 | sample ring+cross | 12 | 18 | ring-size | 244 | 15s |
| 3 | dense | 8 | 24 | none (traffic) | 1,194 | 20s |
| 6 | NetGameSim NGs | 11 | 10 | election | 284 | 25s |
