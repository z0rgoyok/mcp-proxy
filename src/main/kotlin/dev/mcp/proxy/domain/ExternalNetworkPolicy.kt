package dev.mcp.proxy.domain

enum class ExternalNetworkPolicy(
    val value: String,
) {
    Allowed("allowed"),
    Forbidden("forbidden"),
    ;

    companion object {
        fun fromValue(value: String?): ExternalNetworkPolicy {
            return entries.firstOrNull { policy -> policy.value == value } ?: Forbidden
        }
    }
}
