package edu.uic.cs553.graph

import com.typesafe.config.Config
import io.circe.*
import io.circe.parser.*
import org.slf4j.LoggerFactory

import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

/**
 * GraphLoader reads topology data from disk (or classpath) and builds an enriched SimGraph.
 *
 * Two input formats are supported:
 *
 *  1. NetGameSim two-line JSON (produced by setting contentType = "json" in
 *     netgamesim/GenericSimUtilities/src/main/resources/application.conf):
 *       Line 1 — JSON array of NodeObjects (fields: id, storedValue, …)
 *       Line 2 — JSON array of Actions    (fields: fromId, toId, cost, …)
 *
 *  2. Simple single-object JSON (used by the bundled sample graphs):
 *       { "nodes": [{id, storedValue},...], "edges": [{fromId, toId, cost},...] }
 *
 * Edge labels and node PDFs are applied via enrich(), which reads the sim config block.
 *
 * Why separate raw load from enrichment: keeps I/O logic isolated from config-driven
 * business rules, and lets tests feed synthetic raw data directly into enrich().
 */
object GraphLoader:
  private val log = LoggerFactory.getLogger(getClass)

  // Minimal projections — we only need these fields from the full NetGameSim JSON
  case class RawNode(id: Int, storedValue: Double)
  case class RawEdge(fromId: Int, toId: Int, cost: Double, allowedTypes: Option[List[String]] = None)

  // Custom decoders tolerate extra fields (NetGameSim objects carry many more)
  private given Decoder[RawNode] = Decoder.instance { c =>
    for
      id <- c.downField("id").as[Int]
      sv <- c.downField("storedValue").as[Double].orElse(Right(0.0))
    yield RawNode(id, sv)
  }

  // Decoder for simple JSON format: fromId/toId are node IDs directly
  private given Decoder[RawEdge] = Decoder.instance { c =>
    for
      from    <- c.downField("fromId").as[Int]
      to      <- c.downField("toId").as[Int]
      cost    <- c.downField("cost").as[Double].orElse(Right(1.0))
      allowed <- c.downField("allowedTypes").as[Option[List[String]]].orElse(Right(None))
    yield RawEdge(from, to, cost, allowed)
  }

  // NGs action objects store node IDs in fromNode.id/toNode.id;
  // the top-level fromId/toId are internal action IDs, not node IDs
  private val ngsEdgeDecoder: Decoder[RawEdge] = Decoder.instance { c =>
    for
      from <- c.downField("fromNode").downField("id").as[Int]
      to   <- c.downField("toNode").downField("id").as[Int]
      cost <- c.downField("cost").as[Double].orElse(Right(1.0))
    yield RawEdge(from, to, cost)
  }

  /**
   * Load a NetGameSim two-line JSON file (nodes line, edges line).
   * Invoke from: sbt run after configuring netgamesim contentType = "json".
   */
  def loadNetGameSimJson(path: String): Either[String, (List[RawNode], List[RawEdge])] =
    Using(Source.fromFile(path)) { src =>
      val lines = src.getLines().filter(_.trim.nonEmpty).toList
      if lines.size < 2 then
        Left(s"NetGameSim JSON must have 2 non-empty lines, got ${lines.size} in $path")
      else
        for
          nodes <- decode[List[RawNode]](lines.head).left.map(e => s"Node parse error: ${e.getMessage}")
          edges <- decode[List[RawEdge]](lines(1))(using Decoder.decodeList(ngsEdgeDecoder))
                     .left.map(e => s"Edge parse error: ${e.getMessage}")
        yield (nodes, edges)
    }.fold(ex => Left(ex.getMessage), identity)

  /**
   * Load a simple single-object JSON file.
   * Format: { "nodes": [...], "edges": [...] }
   */
  def loadSimpleJson(path: String): Either[String, (List[RawNode], List[RawEdge])] =
    Using(Source.fromFile(path)) { src =>
      parseJsonContent(src.mkString)
    }.fold(ex => Left(ex.getMessage), identity)

  /**
   * Load from a classpath resource (bundled sample graphs under /graphs/).
   */
  def loadFromClasspath(resourcePath: String): Either[String, (List[RawNode], List[RawEdge])] =
    Option(getClass.getResourceAsStream(resourcePath)) match
      case None     => Left(s"Classpath resource not found: $resourcePath")
      case Some(is) =>
        Using(Source.fromInputStream(is)) { src =>
          parseJsonContent(src.mkString)
        }.fold(ex => Left(ex.getMessage), identity)

  private def parseJsonContent(content: String): Either[String, (List[RawNode], List[RawEdge])] =
    for
      root  <- parse(content).left.map(e => s"JSON parse error: ${e.getMessage}")
      nodes <- root.hcursor.downField("nodes").as[List[RawNode]].left.map(e => s"Nodes field error: ${e.getMessage}")
      edges <- root.hcursor.downField("edges").as[List[RawEdge]].left.map(e => s"Edges field error: ${e.getMessage}")
    yield (nodes, edges)

  /**
   * Enrich raw graph data with edge labels and node PDFs from the sim config.
   *
   * Edge label enforcement: every edge gets the configured default allowed types,
   * plus CONTROL (always permitted so algorithm messages can traverse any channel).
   *
   * Node PDF: the configured defaultPdf is applied uniformly; per-node overrides
   * (sim.traffic.perNodePdf) are applied on top.
   *
   * Probabilities are validated to sum to 1.0 ± epsilon; mis-configured PDFs
   * fall back to uniform distribution with a warning.
   */
  def enrich(
    rawNodes: List[RawNode],
    rawEdges: List[RawEdge],
    cfg:      Config,
    seed:     Long
  ): SimGraph =
    val msgTypes       = safeGetStringList(cfg, "sim.messages.types")
    val defaultAllowed = safeGetStringList(cfg, "sim.edgeLabeling.default").toSet + "CONTROL"
    val perEdgeAllowed = buildPerEdgeAllowed(cfg)
    val defaultPdf     = buildDefaultPdf(cfg, msgTypes)
    val timerNodes     = safeGetIntList(cfg, "sim.initiators.timers").toSet
    val inputNodes     = safeGetIntList(cfg, "sim.initiators.inputs").toSet
    val tickMs         = Try(cfg.getLong("sim.traffic.tickIntervalMs")).getOrElse(500L)
    val perNodePdf     = buildPerNodePdf(cfg, msgTypes)

    val nodes = rawNodes.map { rn =>
      val nodePdf = perNodePdf.getOrElse(rn.id, defaultPdf)
      SimNode(
        id           = rn.id,
        storedValue  = rn.storedValue,
        pdf          = nodePdf,
        timerEnabled = timerNodes.contains(rn.id),
        tickEveryMs  = tickMs,
        isInputNode  = inputNodes.contains(rn.id)
      )
    }

    val edges = rawEdges.map { re =>
      // Priority: config perEdge > JSON allowedTypes > config default
      // CONTROL is always added so algorithm messages can traverse any channel
      val allowed = perEdgeAllowed.get((re.fromId, re.toId))
        .orElse(re.allowedTypes.map(_.toSet))
        .getOrElse(defaultAllowed - "CONTROL")
        .+("CONTROL")
      SimEdge(fromId = re.fromId, toId = re.toId, cost = re.cost, allowedTypes = allowed)
    }

    log.info(s"Graph enriched: ${nodes.size} nodes, ${edges.size} edges, seed=$seed")
    SimGraph(nodes, edges, seed)

  // ------- config helpers -------

  private def safeGetStringList(cfg: Config, path: String): List[String] =
    Try(cfg.getStringList(path).asScala.toList).getOrElse(Nil)

  private def safeGetIntList(cfg: Config, path: String): List[Int] =
    Try(cfg.getIntList(path).asScala.map(_.toInt).toList).getOrElse(Nil)

  private def buildDefaultPdf(cfg: Config, msgTypes: List[String]): Map[String, Double] =
    val entries = Try {
      cfg.getConfigList("sim.traffic.defaultPdf").asScala.toList.map { c =>
        c.getString("msg") -> c.getDouble("p")
      }
    }.getOrElse(Nil)

    val pdf = if entries.isEmpty then
      val uniform = if msgTypes.isEmpty then Map("PING" -> 1.0)
                    else msgTypes.map(_ -> (1.0 / msgTypes.size)).toMap
      uniform
    else
      entries.toMap

    validatePdf(pdf, "defaultPdf")

  private def buildPerNodePdf(cfg: Config, msgTypes: List[String]): Map[Int, Map[String, Double]] =
    Try {
      cfg.getConfigList("sim.traffic.perNodePdf").asScala.toList.map { c =>
        val nodeId = c.getInt("node")
        val entries = c.getConfigList("pdf").asScala.toList.map { p =>
          p.getString("msg") -> p.getDouble("p")
        }
        nodeId -> validatePdf(entries.toMap, s"perNodePdf node $nodeId")
      }.toMap
    }.getOrElse(Map.empty)

  private def buildPerEdgeAllowed(cfg: Config): Map[(Int, Int), Set[String]] =
    Try {
      cfg.getConfigList("sim.edgeLabeling.perEdge").asScala.toList.map { c =>
        val from  = c.getInt("fromId")
        val to    = c.getInt("toId")
        val types = c.getStringList("allowedTypes").asScala.toSet
        (from, to) -> types
      }.toMap
    }.getOrElse(Map.empty)

  /** Validate PDF sums to ~1.0; fall back to uniform if not. */
  private def validatePdf(pdf: Map[String, Double], label: String): Map[String, Double] =
    val total = pdf.values.sum
    if math.abs(total - 1.0) > 0.01 then
      log.warn(s"PDF '$label' probabilities sum to $total (expected 1.0); normalising")
      pdf.view.mapValues(_ / total).toMap
    else
      pdf
