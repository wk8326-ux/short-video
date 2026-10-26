package you.deepfuck.shortvideo

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StandaloneEntryTest {
    @Test
    fun bundledSourcesAreAvailableWithoutFetchingOurConfigurationServer() {
        val context = RuntimeEnvironment.getApplication()
        val config = context.assets.open("tvbox.json").bufferedReader().use { JSONObject(it.readText()) }
        val sites = config.getJSONArray("sites")
        assertEquals(5, sites.length())
        for (index in 0 until sites.length()) {
            val site = sites.getJSONObject(index)
            assertEquals(1, site.getInt("type"))
            assertTrue(!site.getString("api").contains("short.deepfuck.you"))
        }
    }

    @Test
    fun launcherDoesNotRequireTheLegacyBackendActivity() {
        val manifest = File("src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        val namespace = "http://schemas.android.com/apk/res/android"
        val activities = document.getElementsByTagName("activity")
        val launchers = (0 until activities.length).map { activities.item(it) as org.w3c.dom.Element }
            .filter { activity ->
                val categories = activity.getElementsByTagName("category")
                (0 until categories.length).any {
                    (categories.item(it) as org.w3c.dom.Element).getAttributeNS(namespace, "name") ==
                        "android.intent.category.LAUNCHER"
                }
            }
        assertEquals(1, launchers.size)
        assertEquals(".TvBoxActivity", launchers.single().getAttributeNS(namespace, "name"))
    }
}
