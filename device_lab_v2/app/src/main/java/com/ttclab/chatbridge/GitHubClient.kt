package com.ttclab.chatbridge

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class GitHubClient(
    private val owner: String,
    private val repo: String,
    private val branch: String,
    private val token: String
) {
    data class RemoteFile(val bytes: ByteArray, val sha: String)

    private val shaCache = ConcurrentHashMap<String, String>()

    suspend fun getFile(path: String): RemoteFile? = withContext(Dispatchers.IO) {
        val url = URL("https://api.github.com/repos/${encode(owner)}/${encode(repo)}/contents/${encodePath(path)}?ref=${encode(branch)}")
        val connection = open(url, "GET")
        val code = connection.responseCode
        if (code == 404) return@withContext null
        val body = readBody(connection)
        if (code !in 200..299) error("GitHub GET $path failed: $code ${body.take(500)}")
        val json = JSONObject(body)
        val content = json.getString("content").replace("\n", "")
        val remote = RemoteFile(Base64.decode(content, Base64.DEFAULT), json.getString("sha"))
        shaCache[path] = remote.sha
        remote
    }

    suspend fun getText(path: String): Pair<String, String>? {
        val file = getFile(path) ?: return null
        return file.bytes.toString(Charsets.UTF_8) to file.sha
    }

    suspend fun putBytes(path: String, bytes: ByteArray, message: String): String {
        var lastFailure: String? = null
        repeat(5) { attempt ->
            val result = putBytesOnce(path, bytes, message)
            if (result.first != null) return result.first!!
            lastFailure = result.second
            delay(200L * (attempt + 1))
        }
        error(lastFailure ?: "GitHub PUT $path failed after retries")
    }

    private suspend fun putBytesOnce(path: String, bytes: ByteArray, message: String): Pair<String?, String?> =
        withContext(Dispatchers.IO) {
            val currentSha = shaCache[path] ?: getFile(path)?.sha
            val payload = JSONObject()
                .put("message", message)
                .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .put("branch", branch)
            if (currentSha != null) payload.put("sha", currentSha)

            val url = URL("https://api.github.com/repos/${encode(owner)}/${encode(repo)}/contents/${encodePath(path)}")
            val connection = open(url, "PUT")
            connection.doOutput = true
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val body = readBody(connection)
            if (code in 200..299) {
                val newSha = JSONObject(body).getJSONObject("content").getString("sha")
                shaCache[path] = newSha
                return@withContext newSha to null
            }
            if (code == 409 || code == 422) {
                shaCache.remove(path)
                return@withContext null to "GitHub PUT $path conflict: $code ${body.take(500)}"
            }
            error("GitHub PUT $path failed: $code ${body.take(500)}")
        }

    suspend fun putText(path: String, text: String, message: String): String =
        putBytes(path, text.toByteArray(Charsets.UTF_8), message)

    private fun open(url: URL, method: String): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "ChatGPT-Device-Lab-Android")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream)).use { it.readText() }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun encodePath(path: String): String = path.split('/').joinToString("/") { encode(it) }
}
