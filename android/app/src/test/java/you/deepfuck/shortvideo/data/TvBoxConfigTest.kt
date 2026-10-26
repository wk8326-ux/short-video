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
        listOf(0, 2, 3, 4).forEach { type ->
            val error = expectConfigError { TvBoxConfigCodec.decode(raw.replace("\"type\":1", "\"type\":$type")) }
            assertTrue(error.message.orEmpty().contains("Example"))
            assertTrue(error.message.orEmpty().contains("$type"))
            assertTrue(error.message.orEmpty().contains("CMS type 1"))
        }
        expectConfigError { TvBoxConfigCodec.decode(raw.replace("https://example.com/api.php/provide/vod/", "csp_Test")) }
        val mixed = raw.replace("]}", ",{\"key\":\"spider\",\"name\":\"Unsupported spider\",\"type\":3,\"api\":\"csp_Test\"}]}")
        val decoded = TvBoxConfigCodec.decode(mixed)
        assertEquals(listOf("cms"), decoded.sites.map { it.key })
        assertEquals(listOf("Unsupported spider（type 3）"), decoded.unsupportedSites)
        assertEquals(decoded, TvBoxConfigCodec.decode(TvBoxConfigCodec.encode(decoded).toString()))
    }

    @Test
    fun invalidDocumentsAndDuplicateKeysAreRejected() {
        listOf("{}", "{\"sites\":[]}", "{\"sites\":[null]}", "garbage", "7bxyz", "{broken").forEach { text ->
            expectConfigError { TvBoxConfigCodec.decode(text) }
        }
        val item = raw.substringAfter("[").substringBeforeLast("]")
        expectConfigError { TvBoxConfigCodec.decode("{\"sites\":[$item,$item]}") }
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

    private fun expectConfigError(action: () -> Unit): TvBoxConfigException {
        try {
            action()
            throw AssertionError("Expected TvBoxConfigException")
        } catch (error: TvBoxConfigException) {
            return error
        }
    }
}
