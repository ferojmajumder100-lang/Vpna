package com.example.util

import android.content.Context
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

data class ActivationStatus(
    val isActivated: Boolean,
    val name: String = "",
    val expiryDate: String = "",
    val message: String = ""
)

object ActivationManager {

    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"
    }

    fun checkActivation(context: Context): ActivationStatus {
        val deviceId = getDeviceId(context).trim()
        if (deviceId == "UNKNOWN_DEVICE") {
            return ActivationStatus(false, message = "Could not retrieve unique Device ID")
        }

        try {
            val url = URL("https://pastebin.com/raw/PCN8A5dD")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.useCaches = false
            conn.setDefaultUseCaches(false)
            conn.setRequestProperty("Cache-Control", "no-cache")

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                conn.disconnect()

                return parseAndVerify(deviceId, response.toString())
            } else {
                conn.disconnect()
                return ActivationStatus(false, message = "Server returned error: ${conn.responseCode}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return ActivationStatus(false, message = "Connection error: ${e.localizedMessage ?: "No Internet Connection"}")
        }
    }

    private fun parseAndVerify(deviceId: String, jsonStr: String): ActivationStatus {
        val normalizedStr = jsonStr.trim()
        if (normalizedStr.isEmpty()) {
            return ActivationStatus(false, message = "Empty database on remote host")
        }

        try {
            // Case 1: Direct JSON Array
            if (normalizedStr.startsWith("[")) {
                val array = JSONArray(normalizedStr)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val status = checkDeviceJsonObject(deviceId, obj)
                    if (status != null) return status
                }
            } else if (normalizedStr.startsWith("{")) {
                val root = JSONObject(normalizedStr)
                
                // Case 2: Object containing a list under "devices" or similar key
                val possibleKeys = listOf("devices", "device_list", "users", "keys", "active")
                for (key in possibleKeys) {
                    if (root.has(key)) {
                        val array = root.optJSONArray(key)
                        if (array != null) {
                            for (i in 0 until array.length()) {
                                val obj = array.optJSONObject(i) ?: continue
                                val status = checkDeviceJsonObject(deviceId, obj)
                                if (status != null) return status
                            }
                        }
                    }
                }

                // Case 3: Object with Device ID as keys: {"device_id": {"name": "...", "expired": "..."}}
                if (root.has(deviceId)) {
                    val devObj = root.optJSONObject(deviceId)
                    if (devObj != null) {
                        val name = devObj.optString("name", devObj.optString("username", "User"))
                        val expired = devObj.optString("expired", devObj.optString("expiry", devObj.optString("expiry_date", "")))
                        return verifyExpiration(name, expired)
                    }
                }

                // Or root itself is a flat key-value list of ids or names
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key.equals(deviceId, ignoreCase = true)) {
                        val value = root.opt(key)
                        if (value is JSONObject) {
                            val name = value.optString("name", "User")
                            val expired = value.optString("expired", value.optString("expiry", ""))
                            return verifyExpiration(name, expired)
                        } else if (value is String) {
                            // If it's just an expiry date string: "device_id": "2026-12-31"
                            return verifyExpiration("User", value)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return ActivationStatus(false, message = "Data format or parsing error: ${e.localizedMessage}")
        }

        return ActivationStatus(false, message = "Device ID not registered")
    }

    private fun checkDeviceJsonObject(deviceId: String, obj: JSONObject): ActivationStatus? {
        val idKeys = listOf("device_id", "id", "deviceId", "dev_id", "key")
        var matched = false
        for (idKey in idKeys) {
            val value = obj.optString(idKey, "")
            if (value.isNotBlank() && value.equals(deviceId, ignoreCase = true)) {
                matched = true
                break
            }
        }

        if (matched) {
            val name = obj.optString("name", obj.optString("username", obj.optString("user", "Active User")))
            val expired = obj.optString("expired", obj.optString("expiry", obj.optString("expiry_date", "")))
            return verifyExpiration(name, expired)
        }
        return null
    }

    private fun verifyExpiration(name: String, expiryStr: String): ActivationStatus {
        if (expiryStr.isBlank()) {
            return ActivationStatus(true, name = name, expiryDate = "Lifetime", message = "Activated")
        }

        val formats = listOf(
            SimpleDateFormat("yyyy-MM-dd", Locale.US),
            SimpleDateFormat("yyyy/MM/dd", Locale.US),
            SimpleDateFormat("dd-MM-yyyy", Locale.US),
            SimpleDateFormat("dd/MM/yyyy", Locale.US)
        )

        var expiryDateObj: Date? = null
        for (fmt in formats) {
            try {
                expiryDateObj = fmt.parse(expiryStr.trim())
                if (expiryDateObj != null) break
            } catch (e: Exception) {
                // Keep checking next format
            }
        }

        if (expiryDateObj == null) {
            // Cannot parse, default to allow but show expiry string
            return ActivationStatus(true, name = name, expiryDate = expiryStr, message = "Activated (Unverified Date Format)")
        }

        val today = Date()
        val isExpired = today.after(expiryDateObj)

        return if (isExpired) {
            ActivationStatus(false, name = name, expiryDate = expiryStr, message = "Subscription expired on $expiryStr")
        } else {
            ActivationStatus(true, name = name, expiryDate = expiryStr, message = "Activated until $expiryStr")
        }
    }
}
