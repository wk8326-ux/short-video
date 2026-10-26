package you.deepfuck.shortvideo.data

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Only the standard JSON CMS protocol (TVBox site type 1) is implemented. */
object TvBoxConfigCodec {
    fun decode(input: String, origin: String? = null): TvBoxConfig {
        if (input.length > MAX_CONFIG_CHARS) throw TvBoxConfigException("TVBox 配置不能超过 4 MB")
        var text = input.trim().removePrefix("\uFEFF").trim()
        repeat(4) {
            if (text.startsWith("{")) return parse(text, origin)
            text = decodeEnvelope(text)
        }
        throw TvBoxConfigException("请输入 JSON、Base64 或十六进制 TVBox 配置")
    }

    private fun parse(text: String, origin: String?): TvBoxConfig {
        val root = try {
            JSONObject(text)
        } catch (error: Exception) {
            throw TvBoxConfigException("TVBox JSON 格式无效：${error.message}", error)
        }
        val sites = root.optJSONArray("sites")
            ?: throw TvBoxConfigException("TVBox 配置缺少 sites 数组")
        if (sites.length() == 0) throw TvBoxConfigException("TVBox 配置没有可用站点")
        val seenKeys = mutableSetOf<String>()
        val unsupported = mutableListOf<String>()
        val parsed = (0 until sites.length()).mapNotNull { index ->
            val site = sites.optJSONObject(index)
                ?: throw TvBoxConfigException("第 ${index + 1} 个站点必须是 JSON 对象")
            val key = site.optString("key").trim()
            val name = site.optString("name").trim().ifEmpty { key }
            if (site.optString("type") != "1") {
                unsupported += "${name.ifBlank { "第 ${index + 1} 个站点" }}（type ${site.opt("type") ?: "未填写"}）"
                return@mapNotNull null
            }
            if (key.isBlank()) throw TvBoxConfigException("第 ${index + 1} 个站点缺少 key")
            if (!seenKeys.add(key)) throw TvBoxConfigException("TVBox 站点 key 重复：$key")
            val api = site.optString("api").trim().toHttpUrlOrNull()
                ?: throw TvBoxConfigException("站点「$name」需要 HTTP(S) CMS API 地址")
            if (api.username.isNotEmpty() || api.password.isNotEmpty()) {
                throw TvBoxConfigException("站点「$name」的 API 地址不能包含账号密码")
            }
            val canonicalApi = api.newBuilder().fragment(null).build().toString()
            TvBoxSite(
                id = "tvbox:${tvBoxDigest("cms1\n$key\n$canonicalApi")}",
                key = key,
                name = name,
                api = canonicalApi,
                searchable = site.optString("searchable", "1") !in setOf("0", "false"),
                enabled = site.optBoolean("enabled", true),
            )
        }
        if (parsed.isEmpty()) {
            throw TvBoxConfigException(
                "TVBox 配置没有支持的站点：${unsupported.joinToString("、")}。" +
                    "仅支持标准 JSON CMS type 1，不支持爬虫、JavaScript 和 AList。",
            )
        }
        return TvBoxConfig(parsed, origin, (savedUnsupportedSites(root) + unsupported).distinct())
    }

    internal fun savedUnsupportedSites(root: JSONObject): List<String> {
        val entries = root.optJSONArray("unsupportedSites") ?: return emptyList()
        return (0 until entries.length()).mapNotNull { index ->
            (entries.opt(index) as? String)?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct()
    }

    private fun decodeEnvelope(input: String): String {
        val text = input.removePrefix("base64://").removePrefix("b64://")
            .removePrefix("data:application/json;base64,").trim()
        val hex = text.removePrefix("hex://").removePrefix("0x").replace(Regex("\\s"), "")
        val bytes = try {
            if (hex.isNotEmpty() && hex.length % 2 == 0 && hex.all { it.digitToIntOrNull(16) != null }) {
                ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
            } else {
                val compact = text.replace(Regex("\\s"), "")
                runCatching { Base64.getDecoder().decode(compact) }
                    .getOrElse { Base64.getUrlDecoder().decode(compact) }
            }
        } catch (error: IllegalArgumentException) {
            throw TvBoxConfigException("请输入 JSON、Base64 或十六进制 TVBox 配置", error)
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
                .toString().trim().removePrefix("\uFEFF").trim()
        } catch (error: Exception) {
            throw TvBoxConfigException("TVBox 配置不是有效的 UTF-8 文本", error)
        }
    }

    internal fun encode(config: TvBoxConfig): JSONObject = JSONObject()
        .put("origin", config.origin ?: JSONObject.NULL)
        .put("unsupportedSites", JSONArray(config.unsupportedSites))
        .put("sites", JSONArray().apply {
            config.sites.forEach { site ->
                put(JSONObject().put("key", site.key).put("name", site.name).put("type", 1)
                    .put("api", site.api).put("searchable", if (site.searchable) 1 else 0).put("enabled", site.enabled))
            }
        })

    internal const val MAX_CONFIG_CHARS = 4 * 1024 * 1024
}

internal fun tvBoxDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun tvBoxId(sourceId: String, kind: String, remoteId: String): Long {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest("$sourceId\n$kind\n$remoteId".toByteArray(Charsets.UTF_8))
    val magnitude = ByteBuffer.wrap(bytes).long and Long.MAX_VALUE
    return -magnitude.coerceAtLeast(1L)
}
