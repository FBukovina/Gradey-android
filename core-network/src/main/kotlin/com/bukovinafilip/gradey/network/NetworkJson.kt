package com.bukovinafilip.gradey.network

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.IOException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

val GradeyJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

open class GradeyApiException(
    val statusCode: Int,
    message: String,
) : RuntimeException(message)

suspend fun OkHttpClient.executeString(request: Request): String = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val body = response.use {
                    val text = it.body?.string().orEmpty()
                    if (!it.isSuccessful) throw GradeyApiException(it.code, text.ifBlank { "HTTP ${it.code}" })
                    text
                }
                if (continuation.isActive) continuation.resume(body)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    })
}

fun formBody(fields: Map<String, String>): FormBody =
    FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()

fun jsonBody(json: String) = json.toRequestBody("application/json; charset=utf-8".toMediaType())

fun String.appendPath(path: String): String =
    trimEnd('/') + "/" + path.trimStart('/')

