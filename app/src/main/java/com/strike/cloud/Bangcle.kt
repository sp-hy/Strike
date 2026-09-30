package com.strike.cloud

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

const val CIPHER_TABLES = "assets/byd/bangcle_tables.bin"

private const val BLOCK = 16
private const val VERSION = 1
private const val HEADER = 8
private const val INDEX_ENTRY = 8

private val SIZES = intArrayOf(0x28000, 0x3C000, 0x1000, 0x28000, 0x3C000, 0x1000, 8, 8)

/**
 * BYD's white-box AES envelope for overseas cloud traffic: "F" + Base64(CBC, zero IV, PKCS#7).
 * The key lives inside the lookup tables from pyBYD's bangcle_tables.bin (MIT).
 */
internal class Bangcle(private val tables: Tables) {

    internal class Tables(
        val invRound: ByteArray,
        val invXor: ByteArray,
        val invFirst: ByteArray,
        val round: ByteArray,
        val xor: ByteArray,
        val final: ByteArray,
        val permDecrypt: ByteArray,
        val permEncrypt: ByteArray
    )

    fun encode(plaintext: String): String {
        val padded = pad(plaintext.toByteArray(Charsets.UTF_8))
        val out = ByteArray(padded.size)
        var prev = ByteArray(BLOCK)
        val mixed = ByteArray(BLOCK)
        for (offset in padded.indices step BLOCK) {
            for (i in 0 until BLOCK) mixed[i] = (padded[offset + i].toInt() xor prev[i].toInt()).toByte()
            prev = encryptBlock(mixed)
            System.arraycopy(prev, 0, out, offset, BLOCK)
        }
        return "F" + Base64.getEncoder().encodeToString(out)
    }

    fun decode(envelope: String): String {
        var cleaned = envelope.replace(Regex("\\s+"), "").replace('-', '+').replace('_', '/')
        require(cleaned.startsWith("F")) { "Bangcle envelope must start with F" }
        cleaned = cleaned.substring(1)
        while (cleaned.length % 4 != 0) cleaned += "="
        val data = Base64.getDecoder().decode(cleaned)
        require(data.isNotEmpty() && data.size % BLOCK == 0) { "Bangcle ciphertext is ${data.size} bytes" }
        val out = ByteArray(data.size)
        val prev = ByteArray(BLOCK)
        for (offset in data.indices step BLOCK) {
            val plain = decryptBlock(data, offset)
            for (i in 0 until BLOCK) out[offset + i] = (plain[i].toInt() xor prev[i].toInt()).toByte()
            System.arraycopy(data, offset, prev, 0, BLOCK)
        }
        return String(unpad(out), Charsets.UTF_8)
    }

    private fun encryptBlock(block: ByteArray): ByteArray =
        rounds(block, 0, tables.permEncrypt, tables.round, tables.xor, 0 until 9) { state, tmp ->
            for (row in 0 until 4) {
                for (lane in 0 until 4) {
                    val source = (lane + row) and 3
                    state[lane * 8 + row] = tables.final[(tmp[lane * 8 + source].toInt() and 0xFF) + source * 0x400 + lane * 0x100]
                }
            }
        }

    private fun decryptBlock(block: ByteArray, offset: Int): ByteArray =
        rounds(block, offset, tables.permDecrypt, tables.invRound, tables.invXor, 9 downTo 1) { state, tmp ->
            for (row in 0 until 4) {
                for (lane in 0 until 4) {
                    val source = (row - lane) and 3
                    state[lane * 8 + row] = tables.invFirst[(tmp[lane * 8 + source].toInt() and 0xFF) + source * 0x400 + lane * 0x100]
                }
            }
        }

    private inline fun rounds(
        block: ByteArray,
        offset: Int,
        perm: ByteArray,
        round: ByteArray,
        xor: ByteArray,
        order: IntProgression,
        last: (ByteArray, ByteArray) -> Unit
    ): ByteArray {
        val state = ByteArray(32)
        val wide = ByteArray(64)
        for (col in 0 until 4) for (row in 0 until 4) state[col * 8 + row] = block[offset + col + row * 4]
        for (rnd in order) {
            for (i in 0 until 4) {
                val shift = perm[i * 2].toInt() and 0xFF
                for (j in 0 until 4) {
                    val lane = (shift + j) and 3
                    val index = ((state[i * 8 + lane].toInt() and 0xFF) + (i + (rnd * 4 + lane) * 4) * 256) * 4
                    System.arraycopy(round, index, wide, i * 16 + j * 4, 4)
                }
            }
            var column = 1
            for (col in 0 until 4) {
                var at = col
                for (row in 0 until 4) {
                    val first = wide[at].toInt() and 0xFF
                    var low = first and 0x0F
                    var high = first and 0xF0
                    val base = row * 0x18 + rnd * 0x60
                    var step = column
                    for (k in 1..3) {
                        val next = wide[at + k * 0x10].toInt() and 0xFF
                        high = ((high shr 4) or ((next shr 4) shl 4)) and 0xFF
                        low = xor[(base + step - 1) * 0x100 + (low or ((next shl 4) and 0xFF))].toInt() and 0x0F
                        high = (xor[(base + step) * 0x100 + high].toInt() and 0x0F) shl 4
                        step += 2
                    }
                    state[row + col * 8] = (high or low).toByte()
                    at += 4
                }
                column += 6
            }
        }
        val tmp = state.copyOf()
        last(state, tmp)
        val out = ByteArray(BLOCK)
        for (col in 0 until 4) for (row in 0 until 4) out[col + row * 4] = state[col * 8 + row]
        return out
    }

    private fun pad(data: ByteArray): ByteArray {
        val pad = BLOCK - data.size % BLOCK
        return data.copyOf(data.size + pad).also { it.fill(pad.toByte(), data.size) }
    }

    private fun unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val pad = data.last().toInt() and 0xFF
        if (pad == 0 || pad > BLOCK) return data
        for (i in data.size - pad until data.size) if ((data[i].toInt() and 0xFF) != pad) return data
        return data.copyOf(data.size - pad)
    }

    companion object {
        fun load(input: InputStream): Bangcle = Bangcle(parse(input.readBytes()))

        internal fun parse(data: ByteArray): Tables {
            if (data.size < HEADER + SIZES.size * INDEX_ENTRY) throw IOException("Bangcle tables are too short")
            if (String(data, 0, 4, Charsets.US_ASCII) != "BGTB") throw IOException("Bangcle tables have the wrong header")
            val header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            header.position(4)
            if (header.short.toInt() and 0xFFFF != VERSION) throw IOException("Unsupported Bangcle table version")
            if (header.short.toInt() and 0xFFFF != SIZES.size) throw IOException("Unexpected Bangcle table count")
            val parts = SIZES.mapIndexed { i, size ->
                val entry = ByteBuffer.wrap(data, HEADER + i * INDEX_ENTRY, INDEX_ENTRY).order(ByteOrder.LITTLE_ENDIAN)
                val at = entry.int
                val length = entry.int
                if (length != size || at < 0 || at + length > data.size) throw IOException("Bangcle table $i is damaged")
                data.copyOfRange(at, at + length)
            }
            return Tables(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5], parts[6], parts[7])
        }
    }
}
