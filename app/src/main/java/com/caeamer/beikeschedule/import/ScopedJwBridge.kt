package com.caeamer.beikeschedule.import

import org.json.JSONObject

/** origin/主框架仍由 JwWebView 校验；这里额外隔离过期、取消及重复回调。 */
internal class ScopedJwBridge(
    private val accepts: (Long) -> Boolean,
    private val delegate: (Long) -> JwBridge,
) : JwBridge {
    override fun dispatch(payload: String) {
        val token = runCatching { JSONObject(payload).optString("requestId").toLongOrNull() }.getOrNull() ?: return
        if (accepts(token)) delegate(token).dispatch(payload)
    }
    override fun onError(message: String) = Unit
    override fun onMessage(fn: String, args: List<String>) = Unit
}
