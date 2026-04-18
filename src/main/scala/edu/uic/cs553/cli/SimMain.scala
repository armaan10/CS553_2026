package edu.uic.cs553.cli

import akka.actor.{ActorRef, ActorSystem}
import com.typesafe.config.{Config, ConfigFactory}
import edu.uic.cs553.algorithms.{DistributedAlgorithm, ItaiRodehElection, ItaiRodehRingSize}
import edu.uic.cs553.graph.GraphLoader
import edu.uic.cs553.sim.{SimCoordinator, SimMessage}
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

/**
 * SimMain is the command-line entry point for all simulation runs.
 *
 * Usage:
 *   sbt "runMain edu.uic.cs553.cli.SimMain [options]"
 *
 * Options:
 *   --config  <name>     Typesafe config name to load (default: application)
 *   --graph   <path>     Path to graph JSON file; omit to use bundled sample
 *   --ngs     <path>     Path to NetGameSim two-line JSON graph file
 *   --algo    <name>     Algorithm to run: election | ring-size | both | none (default: none)
 *   --duration <secs>    Simulation wall-clock duration in seconds (default: 30)
 *   --seed    <long>     Random seed for reproducibility (default: 42)
 *   --inject  <kind>     Inject one external message of this type at startup
 *
 * Examples:
 *   sbt "runMain edu.uic.cs553.cli.SimMain --algo election --duration 20"
 *   sbt "runMain edu.uic.cs553.cli.SimMain --graph outputs/graph.json --algo ring-size"
 *   sbt "runMain edu.uic.cs553.cli.SimMain --config experiments/experiment1 --algo election"
 */
object SimMain:

  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    val opts        = parseArgs(args.toList)
    val cfgName     = opts.getOrElse("config", "application")
    val graphPath   = opts.get("graph")
    val ngsPath     = opts.get("ngs")
    val algo        = opts.getOrElse("algo", "none")
    val durationSec = opts.get("duration").map(_.toLong).getOrElse(30L)
    val seed        = opts.get("seed").map(_.toLong).getOrElse(42L)
    val injectKind  = opts.get("inject")
    val roundsOpt   = opts.get("rounds").map(_.toInt)

    log.info(s"=== CS553 Distributed Sim starting: algo=$algo duration=${durationSec}s seed=$seed ===")

    // Load config (falls back to application.conf if custom config not found)
    val cfg = Try(ConfigFactory.load(cfgName)).getOrElse {
      log.warn(s"Config '$cfgName' not found, falling back to application.conf")
      ConfigFactory.load()
    }

    // Load graph
    val rawGraphE: Either[String, (List[GraphLoader.RawNode], List[GraphLoader.RawEdge])] =
      ngsPath.map(GraphLoader.loadNetGameSimJson)
        .orElse(graphPath.map(GraphLoader.loadSimpleJson))
        .getOrElse(GraphLoader.loadFromClasspath("/graphs/sample-graph.json"))

    val graph = rawGraphE match
      case Right(raw) =>
        val g = GraphLoader.enrich(raw._1, raw._2, cfg, seed)
        log.info(s"Graph loaded: ${g.nodes.size} nodes, ${g.edges.size} edges")
        g
      case Left(err) =>
        log.error(s"Failed to load graph: $err")
        sys.exit(1)
  
    // Max rounds for ring-size: CLI flag > config > default (3)
    val maxRounds = roundsOpt.getOrElse(
      if cfg.hasPath("sim.algorithms.ringSizeMaxRounds")
      then cfg.getInt("sim.algorithms.ringSizeMaxRounds")
      else 3
    )

    // Build algorithm factory (one instance per node; seed differs per node for variety)
    val algoFactory: Int => Option[DistributedAlgorithm] = nodeId =>
      algo match
        case "election"  => Some(ItaiRodehElection(graph.nodes.size, seed + nodeId))
        case "ring-size" => Some(ItaiRodehRingSize(seed + nodeId, maxRounds))
        case "both"      =>
          // Run election first; ring-size runs independently on the same ring
          // We pick one per node: even nodes do election, odd do ring-size
          if nodeId % 2 == 0 then Some(ItaiRodehElection(graph.nodes.size, seed + nodeId))
          else                     Some(ItaiRodehRingSize(seed + nodeId, maxRounds))
        case _           => None

    // Create actor system and start coordinator
    val system      = ActorSystem("cs553-sim", cfg)
    val coordinator = system.actorOf(SimCoordinator.props(graph, algoFactory), "coordinator")

    coordinator ! SimMessage.Start

    // Optional: inject an external message at startup
    injectKind.foreach { kind =>
      coordinator ! SimMessage.ExternalInput(kind, "driver-injection")
      log.info(s"Injected external input kind=$kind")
    }

    // Schedule stop after configured duration
    import system.dispatcher
    system.scheduler.scheduleOnce(durationSec.seconds) {
      coordinator ! SimMessage.Stop
      log.info(s"Stop signal sent after ${durationSec}s")
    }

    // Terminate ActorSystem a few seconds after the stop signal
    system.scheduler.scheduleOnce((durationSec + 6).seconds) {
      system.terminate()
    }

    // Block main thread until system shuts down
    Try(Await.result(system.whenTerminated, (durationSec + 15).seconds)) match
      case Success(_) => log.info("=== Simulation complete ===")
      case Failure(e) => log.error(s"Simulation ended with error: ${e.getMessage}")

  // ------- argument parsing -------

  /**
   * Parses "--key value" pairs from the command-line argument list.
   * Each option must be a "--key" followed immediately by its value.
   */
  private def parseArgs(args: List[String]): Map[String, String] =
    args.sliding(2, 2).collect {
      case List(key, value) if key.startsWith("--") => key.stripPrefix("--") -> value
    }.toMap
