package com.opentokenusage.android.data.network

import com.opentokenusage.android.domain.model.AccountSecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class ProviderProbeException(
    override val message: String,
    val isAuthError: Boolean = false,
    val retryAfterMs: Long? = null,
    val statusCode: Int? = null,
    val updatedSecret: AccountSecret? = null,
) : RuntimeException(message) {
    fun withUpdatedSecret(secret: AccountSecret?): ProviderProbeException =
        if (secret == null || this.updatedSecret != null) {
            this
        } else {
            ProviderProbeException(
                message = message,
                isAuthError = isAuthError,
                retryAfterMs = retryAfterMs,
                statusCode = statusCode,
                updatedSecret = secret,
            )
        }
}

data class HttpResponseData(
    val statusCode: Int,
    val bodyText: String,
    val headers: Map<String, List<String>>,
) {
    fun firstHeader(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

object ProviderHttpSupport {
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val FORM_MEDIA = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun get(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponseData = execute(client, Request.Builder().url(url).get().applyHeaders(headers).build())

    suspend fun postJson(
        client: OkHttpClient,
        url: String,
        bodyJson: String = "{}",
        headers: Map<String, String> = emptyMap(),
    ): HttpResponseData = execute(
        client,
        Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody(JSON_MEDIA))
            .applyHeaders(headers)
            .build(),
    )

    suspend fun postForm(
        client: OkHttpClient,
        url: String,
        formBody: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponseData = execute(
        client,
        Request.Builder()
            .url(url)
            .post(formBody.toRequestBody(FORM_MEDIA))
            .applyHeaders(headers)
            .build(),
    )

    private fun Request.Builder.applyHeaders(headers: Map<String, String>): Request.Builder {
        headers.forEach { (k, v) -> header(k, v) }
        return this
    }

    private suspend fun execute(client: OkHttpClient, request: Request): HttpResponseData =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    HttpResponseData(
                        statusCode = resp.code,
                        bodyText = text,
                        headers = resp.headers.toMultimap(),
                    )
                }
            } catch (e: IOException) {
                throw ProviderProbeException("通信エラー: ${e.message ?: "接続できません"}", isAuthError = false)
            }
        }

    fun tryParseObject(bodyText: String): JsonObject? {
        if (bodyText.isBlank()) return null
        return runCatching { json.parseToJsonElement(bodyText) as? JsonObject }.getOrNull()
    }

    fun tryParseArray(bodyText: String): JsonArray? {
        if (bodyText.isBlank()) return null
        return runCatching { json.parseToJsonElement(bodyText) as? JsonArray }.getOrNull()
    }

    fun parseRetryAfterMs(headerVal: String?, defaultMs: Long = 15 * 60_000L): Long {
        if (headerVal.isNullOrBlank()) return defaultMs
        val trimmed = headerVal.trim()
        trimmed.toLongOrNull()?.let { sec ->
            if (sec > 0L) return (sec * 1000L).coerceAtLeast(30_000L)
        }
        return runCatching {
            val zdt = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
            val diff = zdt.toInstant().toEpochMilli() - System.currentTimeMillis()
            if (diff > 0L) diff else defaultMs
        }.getOrElse { defaultMs }
    }

    fun parseDateMs(value: JsonElement?): Long? {
        val prim = value as? JsonPrimitive ?: return null
        prim.doubleOrNull?.let { num ->
            if (!num.isFinite()) return null
            // Convert seconds vs milliseconds cleanly
            return if (num < 1_000_000_000_000.0) (num * 1000.0).toLong() else num.toLong()
        }
        val text = prim.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        text.toDoubleOrNull()?.let { num ->
            return if (num < 1_000_000_000_000.0) (num * 1000.0).toLong() else num.toLong()
        }
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
    }

    fun parseDateMs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim()
        t.toDoubleOrNull()?.let { num ->
            return if (num < 1_000_000_000_000.0) (num * 1000.0).toLong() else num.toLong()
        }
        return runCatching { Instant.parse(t).toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
    }
}

fun JsonObject.strOrNull(key: String): String? {
    val el = this[key] ?: return null
    if (el is JsonNull) return null
    return (el as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}

fun JsonObject.doubleOrNull(key: String): Double? {
    val el = this[key] ?: return null
    val prim = el as? JsonPrimitive ?: return null
    val num = prim.doubleOrNull ?: prim.contentOrNull?.trim()?.toDoubleOrNull() ?: return null
    return num.takeIf { it.isFinite() }
}

fun JsonObject.longOrNull(key: String): Long? {
    val el = this[key] ?: return null
    val prim = el as? JsonPrimitive ?: return null
    return prim.longOrNull ?: prim.intOrNull?.toLong() ?: prim.doubleOrNull?.toLong()
}

fun JsonObject.boolOrNull(key: String): Boolean? {
    val el = this[key] ?: return null
    return (el as? JsonPrimitive)?.booleanOrNull
}

fun JsonObject.objOrNull(key: String): JsonObject? =
    runCatching { this[key]?.takeIf { it !is JsonNull }?.jsonObject }.getOrNull()

fun JsonObject.arrOrNull(key: String): JsonArray? =
    runCatching { this[key]?.takeIf { it !is JsonNull }?.jsonArray }.getOrNull()
