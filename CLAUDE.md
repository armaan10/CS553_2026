This# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CS553 (Distributed Systems) course project at UIC. Provides an Akka-based framework for implementing and experimenting with distributed algorithms using Akka's typed actor model. The project integrates with **NetGameSim** (git submodule) for graph generation.

## Build & Run Commands

```bash
sbt compile                  # Compile
sbt test                     # Run all tests
sbt "testOnly *DistributedNodeSpec"   # Run a single test spec

# Run examples
sbt "runMain com.uic.cs553.distributed.examples.EchoAlgorithmExample"
sbt "runMain com.uic.cs553.distributed.examples.BullyLeaderElectionExample"
sbt "runMain com.uic.cs553.distributed.examples.TokenRingExample"
```

**Requirements:** Java 11+, SBT 1.9.x

## Architecture

The framework uses a 3-layer structure under `src/main/scala/com/uic/cs553/distributed/`:

### `framework/` — Core abstractions
- **`DistributedNode.scala`**: Abstract base `BaseDistributedNode` that all algorithm nodes extend. Handles peer registration, message routing, and common lifecycle (Initialize, Start, Stop).
- **`ExperimentRunner.scala`**: Entry point utility — creates the Akka `ActorSystem`, spawns a `DistributedSystemCoordinator` actor, wires up nodes with peer references, then starts/stops the experiment.

### `algorithms/` — Algorithm implementations
Each algorithm extends `BaseDistributedNode` and implements its own message protocol on top of `CommonMessages`:
- **`EchoAlgorithm.scala`**: Wave propagation (broadcast) + convergcast (echo collection). Initiator floods outward; leaves echo back to root.
- **`BullyLeaderElection.scala`**: Nodes with higher IDs bully lower-ID nodes. Election triggered when a node notices the coordinator is absent.
- **`TokenRingAlgorithm.scala`**: Mutual exclusion via token passing. Nodes form a logical ring; only the token holder enters the critical section.

### `examples/` — Runnable demos
Thin `@main` objects that call `ExperimentRunner` with a node count and duration. These are the intended entry points when exploring the framework.

### Message flow
```
ExperimentRunner
  └─ spawns DistributedSystemCoordinator
       ├─ creates N algorithm nodes (actors)
       ├─ sends Initialize(peers) to each node
       └─ sends Start → algorithm runs → sends Stop after timeout
```

### Configuration
- `src/main/resources/application.conf` — Akka actor system settings (HOCON)
- `src/main/resources/logback.xml` — logging levels

### NetGameSim submodule
Located at `netgamesim/` (and mirrored at `libs/netgamesim/`). Used for random graph generation as input topology for algorithms. If the submodule is uninitialised, run:
```bash
git submodule update --init --recursive
```

## Tech Stack
- **Scala 2.13.12** + **Akka 2.8.5** (typed actors, cluster, Jackson serialization)
- **ScalaTest 3.2.17** + `akka-actor-testkit-typed` for tests
- **SBT 1.9.7** build tool
