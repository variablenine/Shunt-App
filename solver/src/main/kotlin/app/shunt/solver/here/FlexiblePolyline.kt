/*
 * Decoder for HERE's Flexible Polyline encoding.
 *
 * Ported from the reference implementation at
 * https://github.com/heremaps/flexible-polyline
 *
 * Copyright (C) 2019 HERE Europe B.V.
 * Licensed under the MIT License (SPDX-License-Identifier: MIT).
 * See https://github.com/heremaps/flexible-polyline/blob/master/LICENSE
 */
package app.shunt.solver.here

import app.shunt.core.GeoPoint

object FlexiblePolyline {
    private const val CHARSET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val DECODE_TABLE = IntArray(128) { -1 }.also { table ->
        CHARSET.forEachIndexed { i, c -> table[c.code] = i }
    }
    private const val FORMAT_VERSION = 1L

    /**
     * Decode an encoded polyline to WGS84 points. Any third dimension
     * (elevation etc.) present in the encoding is skipped.
     */
    fun decode(encoded: String): List<GeoPoint> {
        require(encoded.isNotEmpty()) { "empty polyline" }
        val values = VarintReader(encoded)

        val version = values.nextUnsigned()
        require(version == FORMAT_VERSION) { "unsupported polyline version $version" }

        val header = values.nextUnsigned()
        val precision = (header and 15L).toInt()
        val thirdDim = ((header shr 4) and 7L).toInt()
        val factor = pow10(precision)

        val points = mutableListOf<GeoPoint>()
        var lat = 0L
        var lon = 0L
        while (values.hasNext()) {
            lat += values.nextSigned()
            lon += values.nextSigned()
            if (thirdDim != 0) values.nextSigned() // skip elevation/altitude
            points += GeoPoint(lat / factor, lon / factor)
        }
        return points
    }

    private fun pow10(exp: Int): Double {
        var v = 1.0
        repeat(exp) { v *= 10.0 }
        return v
    }

    private class VarintReader(private val encoded: String) {
        private var pos = 0

        fun hasNext(): Boolean = pos < encoded.length

        fun nextUnsigned(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val c = encoded[pos].code
                val value = if (c < 128) DECODE_TABLE[c] else -1
                require(value >= 0) { "invalid polyline character '${encoded[pos]}' at $pos" }
                pos++
                result = result or ((value and 0x1F).toLong() shl shift)
                if (value and 0x20 == 0) return result
                shift += 5
                require(pos < encoded.length) { "truncated polyline" }
            }
        }

        fun nextSigned(): Long {
            val raw = nextUnsigned()
            return if (raw and 1L != 0L) (raw shr 1).inv() else raw shr 1
        }
    }
}
