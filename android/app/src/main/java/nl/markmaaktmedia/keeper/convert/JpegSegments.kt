package nl.markmaaktmedia.keeper.convert

/** Complete JPEG-segmenten (FFxx, lengte, inhoud) om in de uitvoer te zetten. */
object JpegSegments {
    const val APP1 = 0xE1
    const val APP2 = 0xE2

    fun app(marker: Int, payload: ByteArray): ByteArray {
        require(payload.size <= 65533) { "segment te groot: ${payload.size}" }
        val out = ByteArray(payload.size + 4)
        out[0] = 0xFF.toByte(); out[1] = marker.toByte()
        val len = payload.size + 2
        out[2] = (len shr 8).toByte(); out[3] = len.toByte()
        System.arraycopy(payload, 0, out, 4, payload.size)
        return out
    }

    /** Een ICC-profiel in APP2-stukken van maximaal 65519 bytes, genummerd zoals de ICC-regels willen. */
    fun icc(profile: ByteArray): List<ByteArray> {
        val per = 65519
        val count = (profile.size + per - 1) / per
        val id = "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1)
        return (0 until count).map { i ->
            val chunk = profile.copyOfRange(i * per, minOf((i + 1) * per, profile.size))
            app(APP2, id + byteArrayOf((i + 1).toByte(), count.toByte()) + chunk)
        }
    }
}
