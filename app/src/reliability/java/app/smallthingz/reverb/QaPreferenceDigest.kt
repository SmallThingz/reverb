package app.smallthingz.reverb

import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

internal const val QA_PREFERENCE_DIGEST_FORMAT = "sha256:qaprefs-v1:utf16be"

/** QA comparison only; never a persisted preference/recovery wire format or an admission fallback. */
internal fun qaPreferenceDigest(values: Map<String, *>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DataOutputStream(DigestOutputStream(QaDigestSink, digest)).use { output ->
        output.writeInt(0x51505246) // QPRF
        output.writeByte(1) // Encoding version; tags below are explicit, never enum ordinals.
        output.writeInt(values.size)
        for (key in values.keys.sorted()) {
            output.writeQaString(key)
            when (val value = values.getValue(key)) {
                null -> output.writeByte(0)
                is Boolean -> { output.writeByte(1); output.writeBoolean(value) }
                is Int -> { output.writeByte(2); output.writeInt(value) }
                is Long -> { output.writeByte(3); output.writeLong(value) }
                is Float -> { output.writeByte(4); output.writeInt(value.toRawBits()) }
                is String -> { output.writeByte(5); output.writeQaString(value) }
                is Set<*> -> {
                    val strings = value.map {
                        require(it is String) { "QA preference set contains a non-string value" }
                        it
                    }.sorted()
                    output.writeByte(6)
                    output.writeInt(strings.size)
                    strings.forEach(output::writeQaString)
                }
                else -> throw IllegalArgumentException("Unsupported QA preference type: ${value.javaClass.name}")
            }
        }
    }
    val bytes = digest.digest()
    val hex = "0123456789abcdef"
    return CharArray(bytes.size * 2) { index ->
        val value = bytes[index / 2].toInt() and 0xff
        hex[if (index % 2 == 0) value ushr 4 else value and 0xf]
    }.concatToString()
}

private fun DataOutputStream.writeQaString(value: String) {
    // Length counts UTF-16 code units. Unlike replacement-based UTF-8 encoding, this also preserves
    // distinct unpaired surrogate values admitted by the JVM String type.
    writeInt(value.length)
    for (character in value) writeChar(character.code)
}

private object QaDigestSink : OutputStream() {
    override fun write(value: Int) = Unit
    override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
}
