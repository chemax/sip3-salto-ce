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

package io.sip3.salto.ce.domain

import java.nio.charset.Charset
import java.sql.Timestamp

class Packet {

    companion object {

        const val TYPE_RTCP: Byte = 1
        const val TYPE_RTP: Byte = 2
        const val TYPE_SIP: Byte = 3
        const val TYPE_ICMP: Byte = 4
        const val TYPE_RTPR: Byte = 5
        const val TYPE_SMPP: Byte = 6
    }

    lateinit var timestamp: Timestamp

    lateinit var srcAddr: Address
    lateinit var dstAddr: Address

    var protocolCode: Byte = 0
    lateinit var payload: ByteArray

    override fun toString(): String {
        return """
                 |Packet(
                 |       timestamp=$timestamp,
                 |       srcAddr=$srcAddr,
                 |       dstAddr=$dstAddr,
                 |       protocolCode=$protocolCode,
                 |       payload=${payload.toString(Charset.defaultCharset())}
                 |)""".trimMargin()
    }
}