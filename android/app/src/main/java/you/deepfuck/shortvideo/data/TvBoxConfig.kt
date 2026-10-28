package you.deepfuck.shortvideo.data

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Decodes CMS sites and the JAR/JavaScript spider protocol without executing imports. */
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
        val seenIds = mutableSetOf<String>()
        val unsupported = mutableListOf<String>()
        val parsed = (0 until sites.length()).mapNotNull { index ->
            val site = sites.optJSONObject(index)
                ?: throw TvBoxConfigException("第 ${index + 1} 个站点必须是 JSON 对象")
            val key = site.optString("key").trim()
            val name = site.optString("name").trim().ifEmpty { key }
            val type = site.optInt("type", -1)
            if (type !in setOf(1, 3)) {
                unsupported += "${name.ifBlank { "第 ${index + 1} 个站点" }}（type ${site.opt("type") ?: "未填写"}）"
                return@mapNotNull null
            }
            if (key.isBlank()) throw TvBoxConfigException("第 ${index + 1} 个站点缺少 key")
            val siteOrigin = if (site.has("siteOrigin")) site.optString("siteOrigin").takeUnless { it.isBlank() || it == "null" } else origin
            val rawApi = site.optString("api").trim()
            val canonicalApi = if (type == 3 && rawApi.startsWith("csp_")) rawApi
                else resolveHttp(rawApi, siteOrigin, "站点「$name」的 API")
            val rawSpider = site.optString("jar").ifBlank { site.optString("spider") }
                .ifBlank { root.optString("spider") }
            val spider = rawSpider.takeIf { it.isNotBlank() && it != "null" }?.let {
                val parts = it.split(";md5;", limit = 2)
                resolveHttp(parts[0].trim(), siteOrigin, "站点「$name」的爬虫 JAR") +
                    if (parts.size == 2) ";md5;${parts[1].trim()}" else ""
            }
            if (type == 3 && canonicalApi.startsWith("csp_") && spider == null) {
                unsupported += "$name（type 3 缺少 spider / jar 地址）"
                return@mapNotNull null
            }
            val rawExt = site.opt("ext")?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()
            val ext = if (type == 3 && (rawExt.startsWith("./") || rawExt.startsWith("../")))
                resolveHttp(rawExt, siteOrigin, "站点「$name」的规则") else rawExt
            val id = if (type == 1) "tvbox:${tvBoxDigest("cms1\n$key\n$canonicalApi")}" else
                "tvbox:${tvBoxDigest("spider3\n$key\n$canonicalApi\n${spider?.substringBefore(";md5;")}\n${canonicalJson(ext)}") }"
            if (!seenIds.add(id)) return@mapNotNull null
            TvBoxSite(
                id = id,
                key = key,
                name = name,
                api = canonicalApi,
                searchable = site.optString("searchable", "1") !in setOf("0", "false"),
                enabled = site.optBoolean("enabled", true),
                type = type, spider = spider, ext = ext, origin = siteOrigin,
            )
        }
        if (parsed.isEmpty()) {
            throw TvBoxConfigException(
                "TVBox 配置没有支持的站点：${unsupported.joinToString("、")}。" +
                    "支持标准 JSON CMS type 1 和 JAR / JavaScript type 3，暂不支持 AList。",
            )
        }
        val origins = root.optJSONArray("origins")?.let { values ->
            (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }
        } ?: listOfNotNull(origin)
        return TvBoxConfig(parsed, origin, (savedUnsupportedSites(root) + unsupported).distinct(), origins.distinct(),
            root.optJSONArray("removedSites")?.let { entries ->
                (0 until entries.length()).map { entries.getString(it) }
            }.orEmpty())
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
        .put("origins", JSONArray(config.origins))
        .put("removedSites", JSONArray(config.removedSites))
        .put("unsupportedSites", JSONArray(config.unsupportedSites))
        .put("sites", JSONArray().apply {
            config.sites.forEach { site ->
                put(JSONObject().put("key", site.key).put("name", site.name).put("type", site.type)
                    .put("api", site.api).put("searchable", if (site.searchable) 1 else 0).put("enabled", site.enabled)
                    .put("spider", site.spider ?: JSONObject.NULL).put("ext", site.ext)
                    .put("siteOrigin", site.origin ?: JSONObject.NULL))
            }
        })

    /** Existing identities, order and user switches survive imports from any subscription. */
    internal fun merge(previous: TvBoxConfig, incoming: TvBoxConfig, restoreRemoved: Boolean = true): TvBoxConfig {
        val sites = previous.sites.toMutableList()
        val positions = sites.mapIndexed { index, site -> fingerprint(site) to index }.toMap().toMutableMap()
        incoming.sites.forEach { site ->
            val identity = fingerprint(site)
            if (!restoreRemoved && identity in previous.removedSites) return@forEach
            val index = positions[identity]
            if (index == null) {
                positions[identity] = sites.size
                sites += site
            } else {
                val old = sites[index]
                sites[index] = site.copy(id = old.id, key = old.key, enabled = old.enabled, origin = old.origin)
            }
        }
        return TvBoxConfig(sites, incoming.origin ?: previous.origin,
            (previous.unsupportedSites + incoming.unsupportedSites).distinct(),
            (previous.origins + incoming.origins).distinct(),
            if (restoreRemoved) previous.removedSites - incoming.sites.map(::fingerprint).toSet() else previous.removedSites)
    }

    internal fun fingerprint(site: TvBoxSite): String =
        if (site.type == 1) "1\n${site.api}" else "3\n${site.api}\n${site.spider?.substringBefore(";md5;")}\n${canonicalJson(site.ext)}"

    private fun canonicalJson(text: String): String = runCatching {
        fun canonical(value: Any?): String = when (value) {
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
                JSONObject.quote(it) + ":" + canonical(value.opt(it))
            }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
            is String -> JSONObject.quote(value)
            else -> value?.toString() ?: "null"
        }
        canonical(org.json.JSONTokener(text).nextValue())
    }.getOrDefault(text)

    private fun resolveHttp(value: String, origin: String?, description: String): String {
        if (value.isBlank()) throw TvBoxConfigException("$description 不能为空")
        val url = value.toHttpUrlOrNull() ?: origin?.toHttpUrlOrNull()?.resolve(value)
            ?: throw TvBoxConfigException("$description 需要 HTTP(S) 地址；相对路径需要配置 URL")
        if (url.username.isNotEmpty() || url.password.isNotEmpty())
            throw TvBoxConfigException("$description 不能包含账号密码")
        return url.newBuilder().fragment(null).build().toString()
    }

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
