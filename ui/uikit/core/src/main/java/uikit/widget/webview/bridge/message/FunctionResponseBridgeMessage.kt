package uikit.widget.webview.bridge.message

import org.json.JSONObject

data class FunctionResponseBridgeMessage(
    val invocationId: String,
    val status: String,
    val data: Any
): BridgeMessage(Type.FunctionResponse) {

    constructor(invocationId: String, error: Throwable) : this(
        invocationId = invocationId,
        status = "rejected",
        data = error.message ?: "unknown error"
    )

    override fun createJSON(): JSONObject {
        val json = JSONObject()
        json.put("type", type.value)
        json.put("invocationId", invocationId)
        json.put("status", status)
        json.put("data", data)
        return json
    }
}
