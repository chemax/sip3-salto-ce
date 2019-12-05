/*
 * Copyright 2018-2019 SIP3.IO, Inc.
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

package io.sip3.salto.ce.sip

import gov.nist.javax.sip.message.MessageFactoryImpl
import gov.nist.javax.sip.message.SIPMessage
import gov.nist.javax.sip.parser.StringMsgParser
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Metrics
import io.sip3.commons.SipMethods
import io.sip3.commons.util.format
import io.sip3.salto.ce.Routes
import io.sip3.salto.ce.USE_LOCAL_CODEC
import io.sip3.salto.ce.domain.Packet
import io.sip3.salto.ce.util.*
import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Parses SIP messages, calculates related metrics and saves payload to `raw` collection
 */
open class SipMessageHandler : AbstractVerticle() {

    private val logger = KotlinLogging.logger {}

    companion object {

        val SIP_METHODS = SipMethods.values().map(Any::toString).toSet()
    }

    // ISO-8859-1 required in case of SIP-I (to parse binary ISUP)
    init {
        StringMsgParser.setComputeContentLengthFromMessage(true)
        MessageFactoryImpl().setDefaultContentEncodingCharset(Charsets.ISO_8859_1.name())
    }

    private var timeSuffix: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private var exclusions = emptySet<String>()
    private var instances = 1

    private val packetsProcessed = Metrics.counter("packets_processed", "proto", "sip")

    override fun start() {
        config().let { config ->
            config.getString("time-suffix")?.let {
                timeSuffix = DateTimeFormatter.ofPattern(it)
            }

            config.getJsonObject("sip")?.getJsonObject("message")?.getJsonArray("exclusions")?.let {
                exclusions = it.map(Any::toString).toSet()
            }

            config.getJsonObject("vertx")?.getInteger("instances")?.let {
                instances = it
            }
        }

        vertx.eventBus().localConsumer<Packet>(Routes.sip) { event ->
            try {
                val packet = event.body()
                handle(packet)
            } catch (e: Exception) {
                logger.error("SipMessageHandler 'handle()' failed.", e)
            }
        }
    }

    open fun handle(packet: Packet) {
        packetsProcessed.increment()

        var message: SIPMessage? = try {
            StringMsgParser().parseSIPMessage(packet.payload, true, false, null)
        } catch (e: Exception) {
            logger.debug("StringMsgParser `parseSIPMessage()` failed.\n $packet")
            return
        }

        if (message != null && validate(message)) {
            val cseqMethod = message.cseqMethod()

            if (SIP_METHODS.contains(cseqMethod)) {
                val prefix = prefix(cseqMethod!!)
                calculateMetrics(prefix, packet, message)

                if (!exclusions.contains(cseqMethod)) {
                    writeToDatabase(prefix, packet, message)

                    val route = route(prefix, message)
                    vertx.eventBus().send(route, Pair(packet, message), USE_LOCAL_CODEC)
                }
            }
        }
    }

    open fun validate(message: SIPMessage): Boolean {
        return message.callId() != null
                && message.toUri() != null && message.fromUri() != null
    }

    open fun prefix(cseqMethod: String): String {
        return when (cseqMethod) {
            "REGISTER", "NOTIFY", "MESSAGE", "OPTIONS", "SUBSCRIBE" -> Routes.sip + "_${cseqMethod.toLowerCase()}"
            else -> Routes.sip + "_call"
        }
    }

    open fun route(prefix: String, message: SIPMessage): String {
        return when (prefix) {
            Routes.sip + "_call" -> {
                val index = message.callId().hashCode()
                prefix + "_${abs(index % instances)}"
            }
            Routes.sip + "_register" -> {
                // RFC-3261 10.2: The To header field contains the address of record
                // whose registration is to be created, queried, or modified.
                val index = message.toUri().hashCode()
                prefix + "_${abs(index % instances)}"
            }
            else -> prefix
        }
    }

    open fun calculateMetrics(prefix: String, packet: Packet, message: SIPMessage) {
        Counter.builder(prefix + "_messages")
                .apply {
                    packet.srcAddr.host?.let { tag("src_host", it) }
                    packet.dstAddr.host?.let { tag("dst_host", it) }
                    message.cseqMethod()?.let { tag("cseq_method", it) }
                    message.method()?.let { tag("method", it) }
                    message.statusCode()?.let {
                        tag("status_type", "${it / 100}xx")
                        tag("status_code", it.toString())
                    }
                }
                .register(Metrics.globalRegistry)
                .increment()
    }

    open fun writeToDatabase(prefix: String, packet: Packet, message: SIPMessage) {
        val collection = "${prefix}_raw_" + timeSuffix.format(packet.timestamp)

        val document = JsonObject().apply {
            put("document", JsonObject().apply {
                val timestamp = packet.timestamp
                put("created_at", timestamp.time)
                put("nanos", timestamp.nanos)

                val src = packet.srcAddr
                put("src_addr", src.addr)
                put("src_port", src.port)
                src.host?.let { put("src_host", it) }

                val dst = packet.dstAddr
                put("dst_addr", dst.addr)
                put("dst_port", dst.port)
                dst.host?.let { put("dst_host", it) }

                put("call_id", message.callId())
                put("raw_data", String(packet.payload, Charsets.ISO_8859_1))
            })
        }

        vertx.eventBus().send(Routes.mongo_bulk_writer, Pair(collection, document), USE_LOCAL_CODEC)
    }
}