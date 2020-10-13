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

package io.sip3.salto.ce.rtcp

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.sip3.commons.PacketTypes
import io.sip3.commons.domain.SdpSession
import io.sip3.commons.domain.payload.RtpReportPayload
import io.sip3.commons.util.remainingCapacity
import io.sip3.commons.vertx.annotations.Instance
import io.sip3.commons.vertx.util.localRequest
import io.sip3.salto.ce.RoutesCE
import io.sip3.salto.ce.domain.Packet
import io.vertx.core.AbstractVerticle
import mu.KotlinLogging
import java.sql.Timestamp
import kotlin.experimental.and

/**
 * Handles RTCP packets
 */
@Instance
open class RtcpHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val MAX_VALID_JITTER = 10000

        const val R0 = 93.2F
        const val MOS_MIN = 1F
        const val MOS_MAX = 4.5F
    }

    private var bulkSize = 1
    private var expirationDelay: Long = 4000
    private var aggregationTimeout: Long = 30000

    private val sessions = mutableMapOf<Long, RtcpSession>()
    private val sdpSessions = mutableMapOf<Long, SdpSession>()
    private val reports = mutableListOf<Packet>()


    override fun start() {
        context.config().getJsonObject("rtcp")?.let { config ->
            config.getInteger("bulk-size")?.let { bulkSize = it }
            config.getLong("expiration-delay")?.let { expirationDelay = it }
            config.getLong("aggregation-timeout")?.let { aggregationTimeout = it }
        }

        // Periodic task for session expiration
        vertx.setPeriodic(expirationDelay) {
            val now = System.currentTimeMillis()
            // Sessions cleanup
            sessions.filterValues { it.lastPacketTimestamp + aggregationTimeout < now }
                    .forEach { (sessionId, session) ->
                        onSessionExpire(session)
                        sessions.remove(sessionId)
                    }

            // SDP sessions cleanup
            sdpSessions.filterValues { it.timestamp + aggregationTimeout < now }
                    .forEach { (key, _) ->
                        sdpSessions.remove(key)
                    }
        }

        vertx.eventBus().localConsumer<List<Packet>>(RoutesCE.rtcp) { event ->
            val packets = event.body()
            packets.forEach { packet ->
                try {
                    handle(packet)
                } catch (e: Exception) {
                    logger.error("RtprHandler 'handle()' failed.", e)
                }
            }
        }

        vertx.eventBus().localConsumer<List<SdpSession>>(RoutesCE.sdp_info) { event ->
            val sdpInfo = event.body()
            sdpInfo.forEach { sdpSession ->
                try {
                    onSdpSession(sdpSession)
                } catch (e: Exception) {
                    logger.error("RtcpHandler 'onSdpSession()' failed.", e)
                }
            }
        }
    }

    open fun onSdpSession(sdpSession: SdpSession) {
        sdpSessions[sdpSession.id] = sdpSession
    }

    private fun onSessionExpire(session: RtcpSession) {
        // Send cumulative report
        val now = Timestamp(System.currentTimeMillis())
        val rtpReport = Packet().apply {
            timestamp = now
            dstAddr = session.dstAddr
            srcAddr = session.srcAddr
            protocolCode = PacketTypes.RTPR
            session.cumulative.createdAt = now.time
            payload = session.cumulative.encode().array()
        }
        send(rtpReport)
    }

    open fun handle(packet: Packet) {
        val payload = Unpooled.wrappedBuffer(packet.payload)

        while (payload.remainingCapacity() > 4) {
            val offset = payload.readerIndex()

            val headerByte = payload.readByte()
            val payloadType = payload.readUnsignedByte().toInt()
            val reportLength = payload.readUnsignedShort() * 4

            // Return if report is not fully readable
            if (offset + reportLength > payload.capacity()) {
                return
            }

            when (payloadType) {
                // SR: Sender Report RTCP Packet
                200 -> {
                    try {
                        val report = readSenderReport(headerByte, payload)
                        onSenderReport(packet, report)
                    } catch (e: Exception) {
                        logger.trace("RtcpHandler `readSenderReport()` or `onSenderReport()` failed.", e)
                    }
                }
                else -> {
                    // Skip reports:
                    // 201 RR: Receiver Report
                    // 202 SDES: Source Description
                    // 203 BYE: Goodbye
                    // 204 APP: Application-Defined
                    // Undefined RTCP packet
                }
            }

            val nextIndex = offset + reportLength + 4
            if (nextIndex <= payload.capacity()) {
                // Move reader index to next RTCP report in packet
                payload.readerIndex(nextIndex)
            } else {
                // Stop RTCP packet processing
                logger.debug {
                    val src = with(packet.srcAddr) { "${addr}:${port}" }
                    val dst = with(packet.dstAddr) { "${addr}:${port}" }
                    val packetPayload = ByteBufUtil.prettyHexDump(payload.readerIndex(0))
                    "Invalid RTCP packet. Source: $src, Destination: $dst, Packet payload:\n $packetPayload"
                }
                break
            }
        }
    }

    private fun readSenderReport(headerByte: Byte, buffer: ByteBuf): SenderReport {
        return SenderReport().apply {
            reportBlockCount = headerByte.and(31)
            // Sender SSRC
            senderSsrc = buffer.readUnsignedInt()
            // NTP Timestamp: Most and Least significant words
            ntpTimestampMsw = buffer.readUnsignedInt()
            ntpTimestampLsw = buffer.readUnsignedInt()
            // RTP Timestamp
            buffer.skipBytes(4)
            // Sender's packet count
            senderPacketCount = buffer.readUnsignedInt()
            // Sender's octet count
            buffer.skipBytes(4)

            // Reports
            repeat(reportBlockCount.toInt()) {
                reportBlocks.add(RtcpReportBlock().apply {
                    // SSRC of sender
                    ssrc = buffer.readUnsignedInt()
                    // Fraction lost and Cumulative packet lost
                    buffer.readUnsignedInt().let { value ->
                        fractionLost = ((value and 0xF000) shr 24).toShort()
                        cumulativePacketLost = value and 0x0FFF
                    }
                    // Extended sequence number
                    extendedSeqNumber = buffer.readUnsignedInt()
                    // Interarrival Jitter
                    interarrivalJitter = buffer.readUnsignedInt()
                    // Last SR Timestamp
                    lsrTimestamp = buffer.readUnsignedInt()
                    // Delay since last SR
                    buffer.skipBytes(4)
                })
            }
        }
    }

    private fun onSenderReport(packet: Packet, senderReport: SenderReport) {
        senderReport.reportBlocks.forEach { report ->
            val srcPort = packet.srcAddr.port.toLong()
            val dstPort = packet.dstAddr.port.toLong()
            val ssrc = senderReport.senderSsrc.toInt().toLong()
            val sessionId = (srcPort shl 48) or (dstPort shl 32) or ssrc
            var isNewSession = false

            val session = sessions.computeIfAbsent(sessionId) {
                isNewSession = true
                RtcpSession().apply {
                    createdAt = packet.timestamp
                    dstAddr = packet.dstAddr
                    srcAddr = packet.srcAddr
                    this.lastNtpTimestamp = senderReport.ntpTimestamp
                }
            }

            if (session.sdpSession == null) {
                session.sdpSession = sdpSessions[session.dstSdpSessionId] ?: sdpSessions[session.srcSdpSessionId]
            }

            // If interarrival jitter is greater than maximum, current jitter is bad
            if (report.interarrivalJitter < MAX_VALID_JITTER) {
                session.lastJitter = report.interarrivalJitter.toFloat()
            }

            val now = Timestamp(System.currentTimeMillis())
            val rtpReport = Packet().apply {
                timestamp = now
                dstAddr = session.dstAddr
                srcAddr = session.srcAddr
                protocolCode = PacketTypes.RTPR
            }

            val payload = RtpReportPayload().apply {
                createdAt = now.time
                startedAt = if (session.lastPacketTimestamp > 0) {
                    session.lastPacketTimestamp
                } else {
                    now.time
                }

                source = RtpReportPayload.SOURCE_RTCP
                this.ssrc = report.ssrc

                lastJitter = session.lastJitter
                avgJitter = session.lastJitter
                minJitter = session.lastJitter
                maxJitter = session.lastJitter

                if (isNewSession) {
                    receivedPacketCount = senderReport.senderPacketCount.toInt()
                    lostPacketCount = report.cumulativePacketLost.toInt()
                    expectedPacketCount = receivedPacketCount + lostPacketCount
                    fractionLost = lostPacketCount / expectedPacketCount.toFloat()
                } else {
                    expectedPacketCount = (report.extendedSeqNumber - session.previousReport.extendedSeqNumber).toInt()
                    lostPacketCount = (report.cumulativePacketLost - session.previousReport.cumulativePacketLost).toInt()
                    receivedPacketCount = expectedPacketCount - lostPacketCount
                    fractionLost = lostPacketCount / expectedPacketCount.toFloat()
                    duration = (senderReport.ntpTimestamp - session.lastNtpTimestamp).toInt()
                }

                // Perform calculations only if codec information persists
                session.sdpSession?.let { sdpSession ->
                    callId = sdpSession.callId

                    val codec = sdpSession.codec
                    payloadType = codec.payloadType
                    codecName = codec.name

                    // Raw rFactor value
                    val ppl = fractionLost * 100
                    val ieEff = codec.ie + (95 - codec.ie) * ppl / (ppl + codec.bpl)

                    rFactor = (R0 - ieEff)

                    // MoS
                    mos = computeMos(rFactor)
                }
            }

            rtpReport.payload = payload.encode().array()
            updateCumulative(session, payload)

            session.previousReport = report
            session.lastNtpTimestamp = senderReport.ntpTimestamp
            session.lastPacketTimestamp = packet.timestamp.time

            send(rtpReport)
        }
    }

    private fun updateCumulative(session: RtcpSession, payload: RtpReportPayload) {
        session.cumulative.apply {
            if (startedAt == 0L) {
                startedAt = payload.startedAt
                avgJitter = payload.lastJitter
                minJitter = payload.lastJitter
                maxJitter = payload.lastJitter
            }
            if (codecName == null) {
                payload.codecName?.let { codecName = it }
            }
            if (callId == null) {
                payload.callId?.let { callId = it }
            }

            expectedPacketCount += payload.expectedPacketCount
            receivedPacketCount += payload.receivedPacketCount
            rejectedPacketCount += payload.rejectedPacketCount
            lostPacketCount += payload.lostPacketCount

            duration += payload.duration
            fractionLost = lostPacketCount.toFloat() / expectedPacketCount

            lastJitter = payload.lastJitter
            avgJitter = (avgJitter * session.rtcpReportCount + payload.avgJitter) / (session.rtcpReportCount + 1)
            if (maxJitter < lastJitter) {
                maxJitter = lastJitter
            }
            if (minJitter > lastJitter) {
                minJitter = lastJitter
            }

            session.sdpSession?.let { sdpSession ->
                // Raw rFactor value
                var ppl = (1 - receivedPacketCount.toFloat() / expectedPacketCount) * 100
                if (ppl < 0) {
                    ppl = 0F
                }

                val codec = sdpSession.codec
                val ieEff = codec.ie + (95 - codec.ie) * ppl / (ppl + codec.bpl)
                rFactor = (R0 - ieEff)

                // MoS
                mos = computeMos(rFactor)
            }

            session.rtcpReportCount++
        }
    }

    private fun computeMos(rFactor: Float): Float {
        return when {
            rFactor < 0 -> MOS_MIN
            rFactor > 100F -> MOS_MAX
            else -> (1 + rFactor * 0.035 + rFactor * (100 - rFactor) * (rFactor - 60) * 0.000007).toFloat()
        }
    }

    private fun send(rtpReport: Packet) {
        reports.add(rtpReport)
        if (reports.size >= bulkSize) {
            vertx.eventBus().localRequest<Any>(RoutesCE.rtpr, reports.toList())
            reports.clear()
        }
    }
}