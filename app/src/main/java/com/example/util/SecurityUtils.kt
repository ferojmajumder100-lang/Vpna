package com.example.util

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val CHAR_MAP = ALPHABET.withIndex().associate { it.value to it.index }

    fun decode(base32: String): ByteArray {
        val clean = base32.uppercase().replace("-", "").replace(" ", "").trimEnd('=')
        if (clean.isEmpty()) return ByteArray(0)
        
        val byteCount = (clean.length * 5) / 8
        val result = ByteArray(byteCount)
        
        var buffer = 0
        var bitsLeft = 0
        var index = 0
        
        for (char in clean) {
            val value = CHAR_MAP[char] ?: continue
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                if (index < result.size) {
                    result[index++] = ((buffer shr bitsLeft) and 0xFF).toByte()
                }
            }
        }
        return result
    }
}

object Totp {
    fun generateTOTP(secret: String, timeSeconds: Long = System.currentTimeMillis() / 1000): String {
        val key = try {
            Base32.decode(secret)
        } catch (e: Exception) {
            return "Invalid Secret"
        }
        if (key.isEmpty()) return "Invalid Secret"

        val t = timeSeconds / 30
        
        val data = ByteArray(8)
        var temp = t
        for (i in 7 downTo 0) {
            data[i] = (temp and 0xFF).toByte()
            temp = temp shr 8
        }
        
        return try {
            val signKey = SecretKeySpec(key, "HmacSHA1")
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(signKey)
            val hash = mac.doFinal(data)
            
            val offset = hash[hash.size - 1].toInt() and 0xF
            val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                    ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                    ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                    (hash[offset + 3].toInt() and 0xFF)
            
            val otp = binary % 1000000
            String.format("%06d", otp)
        } catch (e: Exception) {
            "Error"
        }
    }
}
