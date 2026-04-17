# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CS553 Distributed Systems course project at UIC. An end-to-end simulator: NetGameSim generates random graphs → `GraphLoader` enriches topology with edge labels + node PDFs → Akka Classic actors run traffic and distributed algorithms.

**Submission deadline:** 2026-04-18 6AM. Main branch is `main`, work branch is `dev`.

## Build & Run Commands

```bash
sbt compile
sbt test
sbt "testOnly edu.uic.cs553.algorithms.ItaiRodehElectionSpec"

# Default run (bundled sample-graph, 30s)
sbt "runMain edu.uic.cs553.cli.SimMain --algo election --duration 20"
sbt "runMain edu.uic.cs553.cli.SimMain --algo ring-size --duration 25"

# With NetGameSim-generated graph
sbt "runMain edu.uic.cs553.cli.SimMain --ngs outputs/NetGraph_xx.ngs.json --algo election"

# Experiments
sbt "runMain edu.uic.cs553.cli.SimMain --config experiments/experiment1 --graph src/main/resources/graphs/sparse-graph.json --algo election --duration 25"
```

**CLI flags:** `--algo election|ring-size|both|none`, `--graph`, `--ngs`, `--duration`, `--seed`, `--inject`, `--config`

**Requirements:** Java 11+, SBT 1.9.x

## Architecture

### Pipeline
```
NetGameSim (outputs/graph.json)
  → GraphLoader (edge labels, node PDFs)  →  SimGraph
  → SimCoordinator (creates NodeActors, sends Init)
  → NodeActors (traffic + algorithm)
  → MetricsCollector (aggregation, summary)
```

### Key source files under `src/main/scala/edu/uic/cs553/`

| Package | File | Purpose |
|---|---|---|
| `graph` | `SimGraph.scala` | Enriched graph model; `ringNextOf` map for ring algorithms |
| `graph` | `GraphLoader.scala` | Parses NetGameSim two-line JSON + simple JSON; PDF normalisation |
| `sim` | `SimMessage.scala` | Sealed message ADT (Init, Envelope, AlgoMessage, …) |
| `sim` | `NodeActor.scala` | Core actor: edge-label enforcement, PDF sampling, timer/input, algorithm plug-in |
| `sim` | `SimCoordinator.scala` | Lifecycle: creates actors, sends Init, routes ExternalInput |
| `sim` | `MetricsCollector.scala` | Aggregates MetricsReport from all nodes, logs summary |
| `algorithms` | `DistributedAlgorithm.scala` | `trait DistributedAlgorithm` + `NodeContext` |
| `algorithms` | `ItaiRodehElection.scala` | Probabilistic leader election for anonymous rings |
| `algorithms` | `ItaiRodehRingSize.scala` | Probe-based ring-size estimation (3-round averaging) |
| `cli` | `SimMain.scala` | Entry point; parses args, loads graph, builds algoFactory, manages lifecycle |

### Message flow
```
SimCoordinator
  ├── creates NodeActor × N  (via algoFactory for algorithm instances)
  ├── sends Init to each (neighbours, allowedOnEdge, pdf, rightNeighborId, ringSize)
  ├── broadcasts Start  → NodeActor.onStart → DistributedAlgorithm.onStart
  ├── timer Tick → PDF sample → Envelope → neighbour (edge-label enforced)
  ├── ExternalInput → random input node → forwards as Envelope
  └── Stop → each node → GetMetrics → MetricsCollector
```

### Configuration files
- `src/main/resources/application.conf` — `sim.*` block: message types, edge labels, PDF, initiators
- `src/main/resources/experiments/experiment{1,2,3}.conf` — 3 experiment configs (inherits via `include "application"`)
- `src/main/resources/graphs/{sample,sparse,dense}-graph.json` — bundled sample graphs
- `src/main/resources/logback.xml` — set `edu.uic.cs553` to DEBUG for verbose tracing

### NetGameSim submodule
Located at `netgamesim/`. To generate JSON graphs:
1. Edit `netgamesim/GenericSimUtilities/src/main/resources/application.conf`: set `outputDirectory` and `contentType = "json"`
2. `cd netgamesim && sbt run` → creates `outputs/NetGraph_<ts>.ngs.json`
3. Load with `--ngs outputs/NetGraph_<ts>.ngs.json`

## Tech Stack
- **Scala 3.3.3** + **Akka Classic 2.8.5** (`akka-actor`, Maven Central, no token needed)
- **circe 0.14.6** for NetGameSim JSON graph parsing
- **ScalaTest 3.2.17** + `akka-testkit` for tests (19 tests, all pass)
- **SBT 1.9.7**
