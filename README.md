# CS553_2026 — Distributed Algorithms Simulator

End-to-end distributed algorithms simulator built with **Scala 3.3.3** and **Akka Classic actors**.  Randomly generated graphs from NetGameSim become running Akka actor networks where each node is an actor and each edge is a message channel.  Two distributed algorithms run on top: **Itai-Rodeh leader election** and **Itai-Rodeh ring size estimation**.

## Prerequisites

| Requirement | Version |
|---|---|
| Java | 11 or higher |
| SBT  | 1.9.x |
| Scala | 3.3.3 (downloaded automatically by SBT) |

## Quick Start (no graph generation needed)

The bundled `sample-graph.json` (12 nodes) is used automatically if no `--graph` flag is provided.

```bash
# Compile
sbt compile

# Run with Itai-Rodeh election (default sample graph, 20s)
sbt "runMain edu.uic.cs553.cli.SimMain --algo election --duration 20"

# Run with ring-size estimation
sbt "runMain edu.uic.cs553.cli.SimMain --algo ring-size --duration 25"

# Run all tests
sbt test
```

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
This creates `outputs/NetGraph_<timestamp>.ngs.json`.

**Step 3** — Run the simulator with the generated graph:
```bash
sbt "runMain edu.uic.cs553.cli.SimMain \
     --ngs outputs/NetGraph_<timestamp>.ngs.json \
     --algo election --duration 30"
```

## Experiment Configurations

Three experiment configs are provided in `src/main/resources/experiments/`:

| Experiment | Graph | Algorithm | Focus |
|---|---|---|---|
| experiment1 | sparse (8 nodes, ring only) | election | Correctness on a pure ring |
| experiment2 | sample (12 nodes, ring+cross) | ring-size | Algorithm isolation from cross-edge traffic |
| experiment3 | dense (8 nodes, high connectivity) | none | Max-throughput and edge-label enforcement |

```bash
# Experiment 1 — pure ring, election
sbt "runMain edu.uic.cs553.cli.SimMain \
     --config experiments/experiment1 \
     --graph src/main/resources/graphs/sparse-graph.json \
     --algo election --duration 25"

# Experiment 2 — sample graph, ring-size
sbt "runMain edu.uic.cs553.cli.SimMain \
     --config experiments/experiment2 \
     --algo ring-size --duration 30"

# Experiment 3 — dense graph, traffic only
sbt "runMain edu.uic.cs553.cli.SimMain \
     --config experiments/experiment3 \
     --graph src/main/resources/graphs/dense-graph.json \
     --algo none --duration 20"
```

## All CLI Options

```
--config  <name>     Typesafe config name (default: application)
--graph   <path>     Simple JSON graph file path
--ngs     <path>     NetGameSim two-line JSON graph file path
--algo    <name>     election | ring-size | both | none  (default: none)
--duration <secs>    Run duration in seconds (default: 30)
--seed    <long>     Random seed for reproducibility (default: 42)
--inject  <kind>     Inject one ExternalInput message of this type at startup
```

## External Input Injection

Input nodes (configured in `sim.initiators.inputs`) accept driver-injected messages:

```bash
# Inject a WORK message at startup
sbt "runMain edu.uic.cs553.cli.SimMain --inject WORK --duration 20"
```

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
└── experiments/     experiment1.conf, experiment2.conf, experiment3.conf

src/test/scala/edu/uic/cs553/
├── graph/           GraphLoaderSpec.scala
├── sim/             NodeActorSpec.scala, SimIntegrationSpec.scala
└── algorithms/      ItaiRodehElectionSpec.scala, ItaiRodehRingSizeSpec.scala

docs/design.md       — architecture and algorithm documentation
netgamesim/          — NetGameSim submodule (graph generation)
outputs/             — generated graph artifacts go here
```

## Architecture Summary

- **GraphLoader** reads NetGameSim JSON output (or bundled sample graphs) and enriches the topology with per-edge allowed message types and per-node probability mass functions.
- **NodeActor** (Akka Classic) enforces edge labels on outgoing traffic, generates background messages via PDF-sampled timers, accepts external injection, and hosts a pluggable `DistributedAlgorithm` instance.
- **SimCoordinator** manages actor lifecycle and routes external inputs.
- **MetricsCollector** aggregates message counts, in-flight estimates, and timing.

See `docs/design.md` for full design rationale and algorithm descriptions.

## Running Tests

```bash
sbt test
# or a single suite:
sbt "testOnly edu.uic.cs553.algorithms.ItaiRodehElectionSpec"
```

## Increasing Log Verbosity

Edit `src/main/resources/logback.xml` and change:
```xml
<logger name="edu.uic.cs553" level="DEBUG" />
```

## NetGameSim Resources

- [NetGameSim repository](https://github.com/0x1DOCD00D/NetGameSim)
- [Code walkthrough video](https://www.youtube.com/watch?v=6fdazJBkdjA&t=2658s)
- [Akka documentation](https://akka.io/docs/)
