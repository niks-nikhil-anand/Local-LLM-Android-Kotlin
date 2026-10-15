package com.example.localai.gguf

import com.example.localai.data.gguf.GgmlType
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Builds small synthetic GGUF files for tests. No real model weights are involved. */
class GgufBuilder(private val version: Int = 3, private val bigEndianVersion: Boolean = false) {

    private data class Tensor(val name: String, val dims: List<Long>, val typeId: Int, val bytes: Long?)

    private val kvs = ByteArrayOutputStream()
    private var kvCount = 0L
    private val tensors = mutableListOf<Tensor>()
    private var declaredKvCount: Long? = null
    private var declaredTensorCount: Long? = null
    private var alignment = 32

    private fun le(size: Int, block: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(block).array()

    private fun ByteArrayOutputStream.u32(v: Long) = write(le(4) { putInt(v.toInt()) })
    private fun ByteArrayOutputStream.u64(v: Long) = write(le(8) { putLong(v) })
    private fun ByteArrayOutputStream.str(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        u64(b.size.toLong())
        write(b)
    }

    private fun key(k: String, type: Int) {
        kvs.str(k)
        kvs.u32(type.toLong())
        kvCount++
    }

    fun string(k: String, v: String) = apply { key(k, 8); kvs.str(v) }
    fun u32(k: String, v: Long) = apply { key(k, 4); kvs.u32(v) }
    fun u64(k: String, v: Long) = apply { key(k, 10); kvs.u64(v) }
    fun bool(k: String, v: Boolean) = apply { key(k, 7); kvs.write(if (v) 1 else 0) }
    fun f32(k: String, v: Float) = apply { key(k, 6); kvs.write(le(4) { putFloat(v) }) }

    fun stringArray(k: String, count: Int) = apply {
        key(k, 9)
        kvs.u32(8)
        kvs.u64(count.toLong())
        repeat(count) { kvs.str("tok$it") }
    }

    fun u32Array(k: String, count: Int) = apply {
        key(k, 9)
        kvs.u32(4)
        kvs.u64(count.toLong())
        repeat(count) { kvs.u32(it.toLong()) }
    }

    /** Array header claiming [count] elements with no element bytes following. */
    fun rawArrayHeader(k: String, elementType: Int, count: Long) = apply {
        key(k, 9)
        kvs.u32(elementType.toLong())
        kvs.u64(count)
    }

    fun rawKey(k: String, type: Int) = apply { key(k, type) }

    fun alignment(value: Int) = apply { alignment = value; u32("general.alignment", value.toLong()) }

    fun tensor(name: String, dims: List<Long>, type: GgmlType) = apply {
        tensors += Tensor(name, dims, type.id, type.tensorBytes(dims))
    }

    fun tensorOfTypeId(name: String, dims: List<Long>, typeId: Int) = apply {
        tensors += Tensor(name, dims, typeId, null)
    }

    fun declareCounts(kv: Long? = null, tensorCount: Long? = null) = apply {
        declaredKvCount = kv
        declaredTensorCount = tensorCount
    }

    /** Header bytes only. Tensor offsets are laid out sequentially at the builder's alignment. */
    fun header(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GGUF".toByteArray(Charsets.US_ASCII))
        if (bigEndianVersion) {
            out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(version).array())
        } else {
            out.u32(version.toLong())
        }
        out.u64(declaredTensorCount ?: tensors.size.toLong())
        out.u64(declaredKvCount ?: kvCount)
        out.write(kvs.toByteArray())
        var offset = 0L
        for (t in tensors) {
            out.str(t.name)
            out.u32(t.dims.size.toLong())
            t.dims.forEach { out.u64(it) }
            out.u32(t.typeId.toLong())
            out.u64(offset)
            offset = ((offset + (t.bytes ?: 0L)) + alignment - 1) / alignment * alignment
        }
        return out.toByteArray()
    }

    /** A complete file: header, alignment padding and zeroed tensor data. */
    fun file(): ByteArray {
        val header = header()
        val dataStart = (header.size + alignment - 1) / alignment * alignment
        val dataEnd = tensors.fold(0L) { offset, t ->
            ((offset + (t.bytes ?: 0L)) + alignment - 1) / alignment * alignment
        }
        val lastTensorEnd = run {
            var offset = 0L
            var end = 0L
            for (t in tensors) {
                end = offset + (t.bytes ?: 0L)
                offset = (end + alignment - 1) / alignment * alignment
            }
            end
        }
        check(dataEnd >= lastTensorEnd)
        return header + ByteArray(dataStart - header.size) + ByteArray(lastTensorEnd.toInt())
    }

    companion object {
        /** A small, valid llama-architecture model used as the baseline for most tests. */
        fun tinyLlama(
            architecture: String = "llama",
            fileType: Long? = 15,
            weightType: GgmlType = GgmlType.Q4_K,
            contextLength: Long = 2048,
            configure: GgufBuilder.() -> Unit = {}
        ): GgufBuilder = GgufBuilder().apply {
            string("general.architecture", architecture)
            string("general.name", "tiny-test")
            string("general.size_label", "1M")
            fileType?.let { u32("general.file_type", it) }
            u32("$architecture.context_length", contextLength)
            u32("$architecture.embedding_length", 64)
            u32("$architecture.block_count", 2)
            u32("$architecture.attention.head_count", 4)
            u32("$architecture.attention.head_count_kv", 2)
            string("tokenizer.ggml.model", "llama")
            string("tokenizer.chat_template", "{{ messages }}")
            stringArray("tokenizer.ggml.tokens", 3)
            tensor("token_embd.weight", listOf(256, 32), weightType)
            tensor("blk.0.attn_q.weight", listOf(256, 32), weightType)
            tensor("output_norm.weight", listOf(64), GgmlType.F32)
            configure()
        }
    }
}
