package cn.luoluna.sweven.ai.net

import cn.luoluna.sweven.ai.tools.custom.HttpToolConfig
import cn.luoluna.sweven.ai.tools.custom.HttpToolExecutor
import cn.luoluna.sweven.ai.tools.local.buildFetchUrlTool
import cn.luoluna.sweven.ai.tools.net.SearchOptions
import cn.luoluna.sweven.ai.tools.net.SearchProviderKind
import cn.luoluna.sweven.ai.tools.net.SearXNGSearchProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual tool entry points must reject before connecting; all targets are literal addresses. */
class ToolOutboundPolicyTest {
    @Test fun fetchToolRejectsLocalMetadataAndPrivateUrls() = runBlocking {
        for (url in listOf("http://127.0.0.1:8787/v1/models", "http://192.168.1.1/", "http://169.254.169.254/latest/meta-data/", "http://[::1]/")) {
            assertPolicyError(buildFetchUrlTool().execute(buildJsonObject { put("url", url) }))
        }
    }

    @Test fun customAndScriptTestSharedExecutorRejectsRenderedUrl() = runBlocking {
        val config = HttpToolConfig(method = "GET", url = "http://{host}/")
        for (host in listOf("127.0.0.1", "192.168.1.1", "169.254.169.254", "100.100.100.200")) {
            assertPolicyError(HttpToolExecutor.run(config, buildJsonObject { put("host", host) }))
        }
    }

    @Test fun arbitrarySearchOptionsCannotGrantLanAccessOrCarryTrustThroughCopy() = runBlocking {
        val options = SearchOptions(kind = SearchProviderKind.SEARXNG, endpoint = "http://192.168.1.1")
        assertFalse(options.permitsLanEndpoint())
        assertFalse(options.copy(endpoint = "http://10.0.0.1").permitsLanEndpoint())
        val error = runCatching { SearXNGSearchProvider.search(options, "q", 1, null, null) }.exceptionOrNull()
        assertTrue(error is OutboundPolicy.Violation)
        assertTrue(error!!.message.orEmpty().contains("不允许"))
    }

    private fun assertPolicyError(result: String) {
        val parsed: JsonObject = ChatJson.json.parseToJsonElement(result).jsonObject
        val error = parsed["error"] as? JsonPrimitive
        assertTrue("Expected a structured policy error, got $result", error != null)
        assertTrue(error!!.jsonPrimitive.content.contains("不允许"))
    }
}
