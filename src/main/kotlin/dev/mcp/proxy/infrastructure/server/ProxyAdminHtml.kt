package dev.mcp.proxy.infrastructure.server

import kotlinx.serialization.json.Json

class ProxyAdminHtml(
    private val json: Json,
) {
    private val htmlTemplate = loadTemplate()

    fun render(status: AdminStatusResponse): String {
        return htmlTemplate.replace("{{INITIAL_STATUS_JSON}}", json.encodeToString(AdminStatusResponse.serializer(), status))
    }

    private fun loadTemplate(): String {
        return checkNotNull(javaClass.classLoader.getResource(TEMPLATE_PATH)) {
            "Admin resource not found: $TEMPLATE_PATH"
        }.readText()
    }

    private companion object {
        const val TEMPLATE_PATH = "admin/index.html"
    }
}
