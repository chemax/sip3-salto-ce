/*
 * Copyright 2018-2020 SIP3.IO, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.sip3.salto.ce.rtpr

import io.netty.buffer.Unpooled
import io.sip3.commons.domain.SdpSession
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.commons.micrometer.Metrics
import io.sip3.commons.util.IpUtil
import io.sip3.commons.util.format
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localRequest
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Address
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.util.MediaUtil.R0
import io.sip3.salto.ce.util.MediaUtil.computeMos
import io.sip3.salto.ce.util.MediaUtil.rtpSessionId
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Handles RTP reports
 */
@Instance(singleton = true)
open class RtprHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val JITTER = "_jitter"
        const val R_FACTOR = "_r-factor"
        const val MOS = "_mos"
        const val EXPECTED_PACKETS = "_expected-packets"
        const val LOST_PACKETS = "_lost-packets"
        const val REJECTED_PACKETS = "_rejected-packets"

        const val DURATION = "_duration"
    }

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private var cumulativeMetrics = true
    private var expirationDelay: Long = 4000
    private var aggregationTimeout: Long = 30000

    private val sdp = mutableMapOf<Long, SdpSession>()
    private val rtp = mutableMapOf<Long, RtprSession>()
    private val rtcp = mutableMapOf<Long, RtprSession>()

    override fun start() {
        config().getString("time-suffix")?.let {
            timeSuffix = DateTimeFormatter.ofPattern(it)
        }

        config().getJsonObject("media")?.getJsonObject("rtp-r")?.let { config ->
            config.getBoolean("cumulative-metrics")?.let {
                cumulativeMetrics = it
            }
            config.getLong("expiration-delay")?.let {
                expirationDelay = it
            }
            config.getLong("aggregation-timeout")?.let {
                aggregationTimeout = it
            }
        }

        vertx.setPeriodic(expirationDelay) {
            terminateExpiredSessions()
        }

        vertx.eventBus().localConsumer<List<SdpSession>>(RoutesCE.sdp + "_info") { event ->
            val sdpSessions = event.body()
            sdpSessions.forEach { sdp[it.id] = it }
        }

        vertx.eventBus().localConsumer<Packet>(RoutesCE.rtpr) { event ->
            try {
                val packet = event.body()
                handleRaw(packet)
            } catch (e: Exception) {
                logger.error(e) { "RtprHandler 'handleRaw()' failed." }
            }
        }

        vertx.eventBus().localConsumer<Pair<Packet, RtpReportPayload>>(RoutesCE.rtpr + "_rtcp") { event ->
            try {
                val (packet, report) = event.body()
                handle(packet, report)
            } catch (e: Exception) {
                logger.error(e) { "RtprHandler 'handle()' failed." }
            }
        }
    }

    open fun handleRaw(packet: Packet) {
        val report = RtpReportPayload().apply {
            val payload = Unpooled.wrappedBuffer(packet.payload)
            decode(payload)
        }

        // Ignore cumulative reports from old SIP3 Captain versions
        if (!report.cumulative) {
            handle(packet, report)
        }
    }

    open fun handle(packet: Packet, report: RtpReportPayload) {
        if (report.callId == null) {
            updateWithSdp(packet, report)
        }

        val sessionId = rtpSessionId(packet.srcAddr, packet.dstAddr, report.ssrc)
        val session = if (report.source == RtpReportPayload.SOURCE_RTP) {
            rtp.getOrPut(sessionId) { RtprSession(packet) }
        } else {
            rtcp.getOrPut(sessionId) { RtprSession(packet) }
        }
        session.add(report)

        val prefix = when (report.source) {
            RtpReportPayload.SOURCE_RTP -> "rtpr_rtp"
            RtpReportPayload.SOURCE_RTCP -> "rtpr_rtcp"
            else -> throw IllegalArgumentException("Unsupported RTP Report source: '${report.source}'")
        }
        writeToDatabase("${prefix}_raw", packet, report)

        if (!cumulativeMetrics) {
            calculateMetrics(prefix, packet.srcAddr, packet.dstAddr, report)
        }
    }

    private fun updateWithSdp(packet: Packet, report: RtpReportPayload) {
        (sdp[sessionId(packet.srcAddr)] ?: sdp[sessionId(packet.dstAddr)])?.let { sdpSession ->
            report.callId = sdpSession.callId

            val codec = sdpSession.codec
            report.payloadType = codec.payloadType
            report.codecName = codec.name

            // Raw rFactor value
            val ppl = report.fractionLost * 100
            val ieEff = codec.ie + (95 - codec.ie) * ppl / (ppl + codec.bpl)

            report.rFactor = (R0 - ieEff)

            // MoS
            report.mos = computeMos(report.rFactor)
        }
    }

    private fun sessionId(address: Address): Long {
        return (IpUtil.convertToInt(address.addr).toLong() shl 32) or (address.port and 0xfffe).toLong()
    }

    private fun terminateExpiredSessions() {
        val now = System.currentTimeMillis()

        sdp.filterValues { it.timestamp + aggregationTimeout < now }
                .forEach { (key, _) -> sdp.remove(key) }

        rtp.filterValues { it.lastReportTimestamp + aggregationTimeout < now }
                .forEach { (sessionId, session) ->
                    terminateRtprSession(session)
                    rtp.remove(sessionId)
                }

        rtcp.filterValues { it.lastReportTimestamp + aggregationTimeout < now }
                .forEach { (sessionId, session) ->
                    terminateRtprSession(session)
                    rtcp.remove(sessionId)
                }
    }

    private fun terminateRtprSession(session: RtprSession) {
        vertx.eventBus().localRequest<Any>(RoutesCE.media, session)

        if (cumulativeMetrics) {
            val prefix = when (session.report.source) {
                RtpReportPayload.SOURCE_RTP -> "rtpr_rtp"
                RtpReportPayload.SOURCE_RTCP -> "rtpr_rtcp"
                else -> throw IllegalArgumentException("Unsupported RTP Report source: '${session.report.source}'")
            }
            calculateMetrics(prefix, session.srcAddr, session.dstAddr, session.report)
        }
    }

    open fun calculateMetrics(prefix: String, src: Address, dst: Address, report: RtpReportPayload) {
        val attributes = mutableMapOf<String, Any>().apply {
            src.host?.let { put("src_host", it) }
            dst.host?.let { put("dst_host", it) }
            put("codec", report.codecName ?: report.payloadType)
        }

        report.apply {
            Metrics.summary(prefix + EXPECTED_PACKETS, attributes).record(expectedPacketCount.toDouble())
            Metrics.summary(prefix + LOST_PACKETS, attributes).record(lostPacketCount.toDouble())
            Metrics.summary(prefix + REJECTED_PACKETS, attributes).record(rejectedPacketCount.toDouble())

            Metrics.summary(prefix + JITTER, attributes).record(avgJitter.toDouble())

            Metrics.summary(prefix + R_FACTOR, attributes).record(rFactor.toDouble())
            Metrics.summary(prefix + MOS, attributes).record(mos.toDouble())

            Metrics.timer(prefix + DURATION, attributes).record(duration.toLong(), TimeUnit.MILLISECONDS)
        }
    }

    open fun writeToDatabase(prefix: String, packet: Packet, report: RtpReportPayload) {
        val collection = prefix + "_" + timeSuffix.format(packet.timestamp)

        val operation = JsonObject().apply {
            put("document", JsonObject().apply {
                put("created_at", report.createdAt)
                put("started_at", report.startedAt)

                val src = packet.srcAddr
                put("src_addr", src.addr)
                put("src_port", src.port)
                src.host?.let { put("src_host", it) }

                val dst = packet.dstAddr
                put("dst_addr", dst.addr)
                put("dst_port", dst.port)
                dst.host?.let { put("dst_host", it) }

                put("payload_type", report.payloadType.toInt())
                put("ssrc", report.ssrc)
                report.callId?.let { put("call_id", it) }
                report.codecName?.let { put("codec_name", it) }
                put("duration", report.duration)

                put("packets", JsonObject().apply {
                    put("expected", report.expectedPacketCount)
                    put("received", report.receivedPacketCount)
                    put("lost", report.lostPacketCount)
                    put("rejected", report.rejectedPacketCount)
                })

                put("jitter", JsonObject().apply {
                    put("last", report.lastJitter.toDouble())
                    put("avg", report.avgJitter.toDouble())
                    put("min", report.minJitter.toDouble())
                    put("max", report.maxJitter.toDouble())
                })

                put("r_factor", report.rFactor.toDouble())
                put("mos", report.mos.toDouble())
                put("fraction_lost", report.fractionLost.toDouble())
            })
        }

        vertx.eventBus().localRequest<Any>(RoutesCE.mongo_bulk_writer, Pair(collection, operation))
    }
}