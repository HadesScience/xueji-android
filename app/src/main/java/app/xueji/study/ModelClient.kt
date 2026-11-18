package app.xueji.study

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

class KeyVault(context: Context) {
    private val preferences = context.getSharedPreferences("model_key", Context.MODE_PRIVATE)
    private val alias = "xueji-model-key"

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getKey(): SecretKey {
        val existing = keyStore().getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    @Synchronized
    fun save(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        check(preferences.edit().putString("iv", encode(cipher.iv))
            .putString("data", encode(cipher.doFinal(value.encodeToByteArray()))).commit()) {
            "无法保存 API 密钥"
        }
    }

    fun contains(): Boolean = preferences.contains("data")

    @Synchronized
    fun read(): String {
        val encrypted = preferences.getString("data", null) ?: return ""
        val iv = preferences.getString("iv", null) ?: error("请重新保存 API 密钥")
        try {
            val key = keyStore().getKey(alias, null) as? SecretKey ?: error("密钥已失效")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            return cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).decodeToString()
        } catch (_: Exception) {
            throw IOException("无法读取 API 密钥，请在设置里重新保存")
        }
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().clear().commit()) { "无法清除 API 密钥" }
    }
}

fun completionUri(endpoint: String): URI {
    val base = runCatching { URI(endpoint.trim()) }.getOrNull()
    require(base != null && base.scheme.equals("https", true) && base.host != null &&
        base.userInfo == null && base.query == null && base.fragment == null) {
        "接口地址需使用 HTTPS，且不能包含账号、查询参数或片段"
    }
    val path = base.path.trimEnd('/')
    return if (path.endsWith("/chat/completions")) base
    else URI("https", null, base.host, base.port, "$path/chat/completions", null, null)
}

class ModelClient(private val vault: KeyVault) {
    suspend fun ask(
        settings: AiSettings,
        system: String,
        messages: List<Message>,
        search: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val uri = completionUri(settings.endpoint)
        val key = vault.read()
        require(key.isNotBlank()) { "请先在「我的 → 模型设置」里保存 API 密钥" }
        require(settings.model.isNotBlank()) { "请填写模型 ID" }

        val messagesJson = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        messages.forEach { message ->
            messagesJson.put(JSONObject().put("role", if (message.user) "user" else "assistant")
                .put("content", message.text))
        }
        val deepSeek = uri.host.equals("api.deepseek.com", true)
        require(!search || deepSeek) { "模型联网搜索需要使用 DeepSeek 官方接口" }
        val model = if (search) "deepseek-flash" else settings.model
        val payload = JSONObject().put("model", model).put("messages", messagesJson)
            .put("stream", false)
        if (deepSeek) {
            payload.put("thinking", JSONObject().put("type", if (settings.thinking) "enabled" else "disabled"))
        }
        if (search) {
            payload.put("tools", JSONArray().put(JSONObject().put("type", "deepseek_web_search")))
        }
        val bytes = payload.toString().encodeToByteArray()
        require(bytes.size <= 300_000) { "本次发送内容过长，请缩短资料或开始新对话" }
        val connection = uri.toURL().openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 90_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            when (status) {
                200 -> Unit
                400, 422 -> error("接口拒绝请求，请核对模型 ID、思考选项和输入长度（HTTP $status）")
                401, 403 -> error("接口未授权，请核对 API 密钥和模型权限（HTTP $status）")
                402 -> error("接口余额不足（HTTP 402）")
                404 -> error("接口地址或模型不存在（HTTP 404）")
                429 -> error("服务请求过多，请稍后重试（HTTP 429）")
                in 300..399 -> error("接口返回跳转，请填写最终的 HTTPS 地址")
                else -> error("模型服务请求失败（HTTP $status）")
            }
            val response = connection.inputStream.use { input -> readBounded(input, 2_000_000) }
            val json = JSONObject(response.decodeToString())
            require(!json.has("error")) { "模型生成失败，可以重试" }
            val choice = json.getJSONArray("choices").getJSONObject(0)
            require(choice.optString("finish_reason") != "length") { "模型输出超出长度上限，请缩小任务范围" }
            val message = choice.getJSONObject("message")
            val result = message.optString("content").trim()
            require(result.isNotBlank() && result != "null") { "模型没有返回可用正文" }
            val annotations = message.optJSONArray("annotations")
            val sources = (0 until (annotations?.length() ?: 0)).mapNotNull { index ->
                val link = annotations?.optJSONObject(index)?.optJSONObject("url_citation") ?: return@mapNotNull null
                val url = link.optString("url")
                if (url.startsWith("https://") || url.startsWith("http://")) url to link.optString("title") else null
            }.distinctBy { it.first }
            if (sources.isEmpty()) result else result + sources.joinToString("\n", "\n\n来源\n") { (url, title) ->
                if (title.isBlank() || title == url) url else "$title\n$url"
            }
        } catch (_: java.net.SocketTimeoutException) {
            throw IOException("模型请求超时，可以重试")
        } catch (_: javax.net.ssl.SSLException) {
            throw IOException("无法建立安全连接，请检查接口证书和设备网络")
        } catch (_: java.net.UnknownHostException) {
            throw IOException("找不到接口地址，请检查网络和域名")
        } catch (_: org.json.JSONException) {
            throw IOException("服务返回了无法读取的响应")
        } finally {
            connection.disconnect()
        }
    }
}

fun jsonObjectFrom(text: String): JSONObject {
    val clean = text.trim().removePrefix("\uFEFF")
    val start = clean.indexOf('{')
    val end = clean.lastIndexOf('}')
    require(start >= 0 && end > start) { "模型返回的结构不符合要求，可以重试" }
    return try {
        JSONObject(clean.substring(start, end + 1))
    } catch (_: org.json.JSONException) {
        throw IllegalArgumentException("模型返回的结构不符合要求，可以重试")
    }
}

fun modelQuestions(output: String): List<Question> {
    val list = jsonObjectFrom(output).optJSONArray("questions")
        ?: error("模型没有返回题目列表，可以重试")
    return (0 until minOf(list.length(), 120)).mapNotNull { index ->
        list.optJSONObject(index)?.let {
            val prompt = it.optString("prompt").take(6000).trim()
            if (prompt.isBlank()) null else Question(prompt = prompt,
                answer = it.optString("answer").take(3000),
                topic = it.optString("topic").take(180),
                explanation = it.optString("explanation").take(4000))
        }
    }.also { require(it.isNotEmpty()) { "模型没有返回可用题目，可以重试" } }
}
