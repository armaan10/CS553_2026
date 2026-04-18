package edu.uic.cs553.sim

import akka.actor.{Actor, ActorLogging, Props, Timers}

import scala.concurrent.duration.*

object MetricsCollector:
  def props(nodeCount: Int): Props = Props(new MetricsCollector(nodeCount))
  private case object Summarise

/**
 * MetricsCollector aggregates MetricsReport messages from all NodeActors.
 *
 * Once all nodeCount reports are received (or a 5-second timeout fires),
 * it logs a consolidated summary: total messages by type, approximate
 * in-flight count per channel, and run timing.
 *
 * Metrics recorded:
 *  - Total messages processed per type (covers the "message counts by type" requirement)
 *  - Per-channel in-flight approximation (covers "approximate in-flight messages per channel")
 *  - Wall-clock run duration (covers "time to completion" metric)
 */
class MetricsCollector(nodeCount: Int) extends Actor with ActorLogging with Timers:
  import SimMessage.*
  import MetricsCollector.*

  private var reports:   List[MetricsReport] = Nil
  private val startedAt: Long                = System.currentTimeMillis()

  // Timeout so we always emit metrics even if some nodes don't respond
  timers.startSingleTimer("timeout", Summarise, 5.seconds)

  override def receive: Receive =
    case r: MetricsReport =>
      reports = r :: reports
      if reports.size >= nodeCount then self ! Summarise

    case Summarise =>
      timers.cancel("timeout")
      summarise()
      context.stop(self)

  private def summarise(): Unit =
    val elapsed = System.currentTimeMillis() - startedAt

    val totalByType   = reports.flatMap(_.msgCountsByType.toSeq)
                               .groupMapReduce(_._1)(_._2)(_ + _)
    val totalInFlight = reports.flatMap(_.inFlightApprox.toSeq)
                               .groupMapReduce(_._1)(_._2)(_ + _)
    val totalMsgs     = totalByType.values.sum

    log.info("======= SIMULATION METRICS =======")
    log.info(s"Reports received:       ${reports.size} / $nodeCount nodes")
    log.info(s"Wall-clock duration:    ${elapsed}ms")
    log.info(s"Total messages:         $totalMsgs")
    log.info("Messages by type:")
    totalByType.toList.sortBy(-_._2).foreach { (kind, count) =>
      log.info(f"  $kind%-12s  $count%6d")
    }
    log.info("In-flight approx per channel (top 10):")
    totalInFlight.toList.sortBy(-_._2).take(10).foreach { (toId, count) =>
      log.info(f"  -> node-$toId%-4d  ~$count%d messages")
    }
    log.info("==================================")
