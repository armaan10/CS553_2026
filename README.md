# CS553_2026 — Distributed Algorithms Simulator
Student: Armaan Ashfaque

UIN: 678073041

End-to-end distributed algorithms simulator built with **Scala 3.3.3** and **Akka Classic actors**.  Randomly generated graphs from NetGameSim become running Akka actor networks where each node is an actor and each edge is a typed message channel.  Two distributed algorithms run on top: **Itai-Rodeh leader election** and **Itai-Rodeh ring size estimation**.

## Prerequisites

| Requirement | Version |
|---|---|
| Java | 11 or higher |
| SBT  | 1.9.x |
| Scala | 3.3.3 (downloaded automatically by SBT) |

---
### Demo Video:
https://drive.google.com/file/d/1sx43qvF07a8VHYucqh0OthhP8ME8-Z9h/view?usp=sharing

Youtube link:
https://youtu.be/yMT_C_rQVFE
## How to Run

### Step 1 — Clone and build

```bash
git clone --recurse-submodules <repo-url>
cd CS553_2026
sbt compile
```

### Step 2 — Run the tests (verify everything works)

```bash
sbt test
```

All 19 tests should pass.

### Step 3 — Run an algorithm

The bundled 12-node `sample-graph.json` is used automatically when no `--graph` flag is provided.

**Leader election:**
```bash
sbt "runMain edu.uic.cs553.cli.SimMain --algo election --duration 20"
```

**Ring size estimation:**
```bash
sbt "runMain edu.uic.cs553.cli.SimMain --algo ring-size --duration 20"
```


**Background traffic only (no algorithm):**
```bash
sbt "runMain edu.uic.cs553.cli.SimMain --algo none --duration 20"
```

### Step 4 — Run a pre-built experiment

```bash

# Experiment 6 — NetGameSim graph, per-edge labels + election
sbt "runMain edu.uic.cs553.cli.SimMain \
     --ngs outputs/NetGraph_18-04-26-05-03-31.ngs \
     --config experiments/experiment6 \
     --algo election --duration 25"

# Experiment 1 — sparse ring (8 nodes), leader election
sbt "runMain edu.uic.cs553.cli.SimMain \
     --config experiments/experiment1 \
     --graph src/main/resources/graphs/sparse-graph.json \
     --algo election --duration 25"

# Experiment 2 — 12-node sample graph, ring-size estimation
sbt "runMain edu.uic.cs553.cli.SimMain \
     --config experiments/experiment2 \
     --algo ring-size --duration 30"

# Experiment 3 — dense graph, traffic throughput only
sbt "runMain edu.uic.cs553.cli.SimMain \
     --config experiments/experiment3 \
     --graph src/main/resources/graphs/dense-graph.json \
     --algo none --duration 20"


```

---

## Graph Generation with NetGameSim

NetGameSim (git submodule in `netgamesim/`) generates the graph topology.

**Step 1** — Configure NetGameSim output:

Edit `netgamesim/GenericSimUtilities/src/main/resources/application.conf`:
```hocon
NGSimulator {
  outputDirectory = "/absolute/path/to/CS553_2026/outputs/"
  OutputGraphRepresentation {
    contentType = "json"   # change from "ngs" to "json"
  }
}
```

**Step 2** — Generate a graph:
```bash
cd netgamesim
sbt run
cd ..
```
This creates `outputs/NetGraph_<timestamp>.ngs`.

**Step 3** — Run the simulator with the generated graph:
```bash
sbt "runMain edu.uic.cs553.cli.SimMain \
     --ngs outputs/NetGraph_<timestamp>.ngs \
     --algo election --duration 30"
```

---

## All CLI Options

```
--config  <name>     Typesafe config name (default: application)
--graph   <path>     Simple JSON graph file path
--ngs     <path>     NetGameSim two-line JSON graph file path
--algo    <name>     election | ring-size | none  (default: none)
--duration <secs>    Run duration in seconds (default: 30)
--seed    <long>     Random seed for reproducibility (default: 42)
--inject  <kind>     Inject one ExternalInput message of this type at startup
```

---

## Increasing Log Verbosity

By default only algorithm events (election rounds, ring-size convergence, leader declaration) are printed.
To also see every individual message sent and received on each channel, set both the Akka level and the logback level to DEBUG.

**Step 1** — `src/main/resources/application.conf`:
```hocon
akka {
  loglevel = "DEBUG"   # change from INFO
  ...
}
```

**Step 2** — `src/main/resources/logback.xml`:
```xml
<logger name="edu.uic.cs553" level="DEBUG" additivity="false" />
```

Both must be set — Akka filters messages before they reach logback, so setting only logback has no effect.

---

## Project Structure

```
src/main/scala/edu/uic/cs553/
├── graph/           SimGraph.scala, GraphLoader.scala
├── sim/             SimMessage.scala, NodeActor.scala, SimCoordinator.scala, MetricsCollector.scala
├── algorithms/      DistributedAlgorithm.scala, ItaiRodehElection.scala, ItaiRodehRingSize.scala
└── cli/             SimMain.scala

src/main/resources/
├── application.conf
├── logback.xml
├── graphs/          sample-graph.json, sparse-graph.json, dense-graph.json
└── experiments/     experiment1.conf, experiment2.conf, experiment3.conf, experiment6.conf

src/test/scala/edu/uic/cs553/
├── graph/           GraphLoaderSpec.scala
├── sim/             NodeActorSpec.scala, SimIntegrationSpec.scala
└── algorithms/      ItaiRodehElectionSpec.scala, ItaiRodehRingSizeSpec.scala

docs/design.md       — architecture and algorithm documentation
netgamesim/          — NetGameSim submodule (graph generation)
outputs/             — generated graph artifacts go here
```

---

## Architecture Summary

- **GraphLoader** reads NetGameSim JSON output (or bundled sample graphs) and enriches the topology with per-edge allowed message types and per-node probability mass functions.
- **SimGraph** sorts all node IDs to build a logical ring overlay (`ringNextOf`) used by both algorithms.
- **NodeActor** (Akka Classic) enforces edge labels on outgoing traffic, generates background messages via PDF-sampled timers, accepts external injection, and hosts a pluggable `DistributedAlgorithm` instance.
- **SimCoordinator** manages actor lifecycle and routes external inputs.
- **MetricsCollector** aggregates message counts, in-flight estimates, and timing.

See `docs/design.md` for full design rationale and algorithm descriptions.

---

## References
- Grechanik, M. *Distributed Algorithms and Systems* 
- Fokkink, W. *Distributed Algorithms: An Intuitive Approach*, MIT Press 
- [NetGameSim repository](https://github.com/0x1DOCD00D/NetGameSim)
- [NetGameSim walkthrough video](https://www.youtube.com/watch?v=6fdazJBkdjA&t=2658s)
- [Akka Classic documentation](https://akka.io/docs/)
