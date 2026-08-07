package fuck.andes.hook.lenovo

import fuck.andes.agent.runtime.AgentExternalArchivePayload
import fuck.andes.agent.runtime.AgentRuntimeWire
import org.json.JSONObject

internal object LenovoXiaoTianHandoff {
    const val SOURCE = "lenovo_xiaotian"

    fun create(runId: String, prompt: String): AgentRuntimeWire.EntryHandoff =
        AgentRuntimeWire.EntryHandoff(
            id = runId,
            source = SOURCE,
            dismissEntrySurfaceOnForegroundOperation = true,
            payload = AgentExternalArchivePayload(
                userText = prompt,
                conversationKey = "xiaotian:$runId",
                title = "天禧：${prompt.lineSequence().firstOrNull()?.trim()?.take(20).orEmpty()}",
                adapterPayload = JSONObject().put("package", "com.lenovo.menu_assistant.hd"),
            ).toJson(),
        )
}
