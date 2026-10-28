package you.deepfuck.shortvideo.data

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TvBoxConfigTest {
    private val raw = """{"sites":[{"key":"cms","name":"Example","type":1,"api":"https://example.com/api.php/provide/vod/"}]}"""

    @Test
    fun plainBase64AndHexDecodeToTheSameStableSite() {
        val encoded = Base64.getEncoder().encodeToString(raw.toByteArray())
        val hex = raw.toByteArray().joinToString("") { "%02x".format(it) }
        val expected = TvBoxConfigCodec.decode(raw)

        assertEquals(expected, TvBoxConfigCodec.decode("\uFEFF $raw"))
        assertEquals(expected, TvBoxConfigCodec.decode(encoded))
        assertEquals(expected, TvBoxConfigCodec.decode("base64://$encoded"))
        assertEquals(expected, TvBoxConfigCodec.decode("hex://$hex"))
        assertEquals(expected, TvBoxConfigCodec.decode("0x$hex"))
        assertEquals(expected, TvBoxConfigCodec.decode(Base64.getEncoder().encodeToString(hex.toByteArray())))
    }

    @Test
    fun sourceIdentityIgnoresDisplayNameAndOriginButScopesApiAndKey() {
        val site = TvBoxConfigCodec.decode(raw).sites.single()
        assertEquals(site.id, TvBoxConfigCodec.decode(raw.replace("Example", "Renamed"), "https://another.test/config").sites.single().id)
        assertNotEquals(site.id, TvBoxConfigCodec.decode(raw.replace("example.com", "other.com")).sites.single().id)
        assertNotEquals(site.id, TvBoxConfigCodec.decode(raw.replace("\"cms\"", "\"other-key\"")).sites.single().id)
    }

    @Test
    fun unsupportedOnlyConfigurationHasAnExplicitError() {
        listOf(0, 2, 4).forEach { type ->
            val error = expectConfigError { TvBoxConfigCodec.decode(raw.replace("\"type\":1", "\"type\":$type")) }
            assertTrue(error.message.orEmpty().contains("Example"))
            assertTrue(error.message.orEmpty().contains("$type"))
            assertTrue(error.message.orEmpty().contains("CMS type 1"))
        }
        expectConfigError { TvBoxConfigCodec.decode(raw.replace("https://example.com/api.php/provide/vod/", "csp_Test")) }
        val mixed = raw.replace("]}", ",{\"key\":\"spider\",\"name\":\"Unsupported spider\",\"type\":4,\"api\":\"csp_Test\"}]}")
        val decoded = TvBoxConfigCodec.decode(mixed)
        assertEquals(listOf("cms"), decoded.sites.map { it.key })
        assertEquals(listOf("Unsupported spider（type 4）"), decoded.unsupportedSites)
        assertEquals(decoded, TvBoxConfigCodec.decode(TvBoxConfigCodec.encode(decoded).toString()))
    }

    @Test
    fun invalidDocumentsAndDuplicateKeysAreRejected() {
        listOf("{}", "{\"sites\":[]}", "{\"sites\":[null]}", "garbage", "7bxyz", "{broken").forEach { text ->
            expectConfigError { TvBoxConfigCodec.decode(text) }
        }
        val item = raw.substringAfter("[").substringBeforeLast("]")
        assertEquals(1, TvBoxConfigCodec.decode("{\"sites\":[$item,$item]}").sites.size)
        expectConfigError { TvBoxConfigCodec.decode(raw.replace("https://example.com", "file:///example.com")) }
        expectConfigError { TvBoxConfigCodec.decode(raw.replace("https://example.com", "https://user:pass@example.com")) }
    }

    @Test
    fun settingsFlagsRoundTripWithoutChangingIdentity() {
        val parsed = TvBoxConfigCodec.decode(raw.replace("\"type\":1", "\"enabled\":false,\"searchable\":0,\"type\":1"))
        assertFalse(parsed.sites.single().enabled)
        assertFalse(parsed.sites.single().searchable)
        assertEquals(parsed, TvBoxConfigCodec.decode(TvBoxConfigCodec.encode(parsed).toString()))
        assertEquals(TvBoxConfigCodec.decode(raw).sites.single().id, parsed.sites.single().id)
    }

    @Test
    fun stableNegativeIdsKeepSourceAndEntityNamespacesSeparate() {
        val id = tvBoxId("source-a", "movie", "42")
        assertTrue(id < 0L)
        assertEquals(id, tvBoxId("source-a", "movie", "42"))
        assertNotEquals(id, tvBoxId("source-b", "movie", "42"))
        assertNotEquals(id, tvBoxId("source-a", "episode", "42"))
    }

    @Test
    fun jarAndJavascriptSitesRetainPerConfigRuntimeAndRelativePaths() {
        val input = """{"spider":"./spider.jar;md5;0123456789abcdef0123456789abcdef","sites":[
            {"key":"plugin","name":"Plugin","type":3,"api":"csp_Test","ext":{"url":"https://site.test/","token":"a"}},
            {"key":"js","type":3,"api":"./script.js","ext":"./rule.js"}]}"""
        val parsed = TvBoxConfigCodec.decode(input, "https://config.test/path/config.json")
        assertEquals(listOf(3, 3), parsed.sites.map { it.type })
        assertEquals("https://config.test/path/spider.jar;md5;0123456789abcdef0123456789abcdef", parsed.sites[0].spider)
        assertEquals("https://config.test/path/script.js", parsed.sites[1].api)
        assertEquals("https://config.test/path/rule.js", parsed.sites[1].ext)
        assertEquals(parsed, TvBoxConfigCodec.decode(TvBoxConfigCodec.encode(parsed).toString(), parsed.origin))
        val other = TvBoxConfigCodec.decode(input.replace("spider.jar", "other.jar"), parsed.origin)
        assertNotEquals(parsed.sites[0].id, other.sites[0].id)
    }

    @Test
    fun jarChecksumUpdatesDoNotDuplicateSourcesOrChangeTheirMovieIdentity() {
        val first = TvBoxConfigCodec.decode("""{"spider":"https://plugins.test/spider.jar;md5;0123456789abcdef0123456789abcdef",
          "sites":[{"key":"plugin","type":3,"api":"csp_Test","ext":{"b":2,"a":1}}]}""")
        val updated = TvBoxConfigCodec.decode("""{"spider":"https://plugins.test/spider.jar;md5;aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "sites":[{"key":"plugin","type":3,"api":"csp_Test","ext":{"a":1,"b":2}}]}""")
        val merged = TvBoxConfigCodec.merge(first, updated)
        assertEquals(1, merged.sites.size)
        assertEquals(first.sites.single().id, merged.sites.single().id)
        assertEquals(merged, TvBoxConfigCodec.decode(TvBoxConfigCodec.encode(merged).toString()))
        assertTrue(merged.sites.single().spider!!.endsWith("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        expectConfigError { TvBoxConfigCodec.decode(raw.replace("https://example.com/api.php/provide/vod/", ""), "https://config.test/config.json") }
    }

    @Test
    fun sameKeyAcrossConfigsDoesNotOverwriteDifferentApisAndAliasesDeduplicate() {
        val first = TvBoxConfigCodec.decode(raw)
        val other = TvBoxConfigCodec.decode(raw.replace("example.com", "other.test"))
        val merged = TvBoxConfigCodec.merge(first, other)
        assertEquals(2, merged.sites.size)
        assertEquals(merged, TvBoxConfigCodec.merge(merged, other))
        val alias = TvBoxConfigCodec.decode(raw.replace("\"cms\"", "\"alias\"").replace("Example", "New title"))
        val updated = TvBoxConfigCodec.merge(merged, alias)
        assertEquals(2, updated.sites.size)
        assertEquals(first.sites[0].id, updated.sites[0].id)
        assertEquals("New title", updated.sites[0].name)
    }

    private fun expectConfigError(action: () -> Unit): TvBoxConfigException {
        try {
            action()
            throw AssertionError("Expected TvBoxConfigException")
        } catch (error: TvBoxConfigException) {
            return error
        }
    }
}
