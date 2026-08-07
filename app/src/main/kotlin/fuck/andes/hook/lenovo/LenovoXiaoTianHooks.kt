package fuck.andes.hook.lenovo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.agent.runtime.AgentAppContext
import fuck.andes.agent.runtime.AgentRuntimeClient
import fuck.andes.agent.runtime.AgentRuntimeWire
import fuck.andes.config.Prefs
import fuck.andes.core.HookInstallation
import fuck.andes.core.HookRegistrar
import fuck.andes.core.HookSupport
import fuck.andes.core.ModuleConfig
import fuck.andes.core.ModuleLogger
import fuck.andes.core.safeLogType
import io.github.libxposed.api.XposedModule
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

internal object LenovoXiaoTianHooks {
    private const val MAX_QUERY_CHARS = 32_000
    private const val DEDUP_WINDOW_MS = 2_000L
    private val recentQueries = ConcurrentHashMap<String, Long>()

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "LenovoXiaoTian")
        return hooks.install {
            val activityClass = HookSupport.findClassOrNull(
                classLoader,
                ModuleConfig.LENOVO_XIAOTIAN_ACTIVITY_CLASS,
            )
            if (activityClass == null) {
                missing(
                    id = "lenovo.activity",
                    description = "天禧智能体 AiChatActivity",
                    detail = "未找到联想天禧智能体文本入口 Activity",
                )
                return@install
            }

            val onCreate = HookSupport.findMethod(activityClass, "onCreate", Bundle::class.java)
            if (onCreate == null) {
                missing(
                    id = "lenovo.activity-create",
                    description = "天禧智能体 AiChatActivity.onCreate",
                    detail = "未找到联想天禧智能体 Activity.onCreate(Bundle)",
                )
            } else {
                intercept(
                    id = "lenovo.activity-create",
                    executable = onCreate,
                    description = "天禧智能体 AiChatActivity.onCreate",
                ) { chain ->
                    val result = chain.proceed()
                    handleIntent(
                        activity = chain.thisObject as? Activity,
                        intent = (chain.thisObject as? Activity)?.intent,
                        logger = logger,
                    )
                    result
                }
            }

            val onNewIntent = HookSupport.findMethod(activityClass, "onNewIntent", Intent::class.java)
            if (onNewIntent == null) {
                missing(
                    id = "lenovo.activity-new-intent",
                    description = "天禧智能体 AiChatActivity.onNewIntent",
                    detail = "未找到联想天禧智能体 Activity.onNewIntent(Intent)",
                )
            } else {
                intercept(
                    id = "lenovo.activity-new-intent",
                    executable = onNewIntent,
                    description = "天禧智能体 AiChatActivity.onNewIntent",
                ) { chain ->
                    val result = chain.proceed()
                    handleIntent(
                        activity = chain.thisObject as? Activity,
                        intent = chain.getArg(0) as? Intent,
                        logger = logger,
                    )
                    result
                }
            }
        }
    }

    private fun handleIntent(
        activity: Activity?,
        intent: Intent?,
        logger: ModuleLogger,
    ) {
        val context = activity ?: AgentAppContext.resolve() ?: return
        if (!isSupportedVersion(context)) return
        val prompt = intent
            ?.getStringExtra(ModuleConfig.LENOVO_XIAOTIAN_QUERY_EXTRA)
            ?.trim()
            ?.take(MAX_QUERY_CHARS)
            ?.takeIf(String::isNotBlank)
            ?: return
        if (!Prefs.isEnabled(Prefs.Keys.AGENT_CUSTOM_MODEL)) return
        if (Prefs.isEnabled(Prefs.Keys.AGENT_REQUIRE_PREFIX) && !prompt.startsWith("/agent ")) {
            return
        }
        val normalizedPrompt = prompt.removePrefix("/agent ").trim().takeIf(String::isNotBlank)
            ?: return
        val dedupKey = "${context.packageName}:$normalizedPrompt"
        val now = System.currentTimeMillis()
        val previous = recentQueries.put(dedupKey, now)
        recentQueries.entries.removeIf { now - it.value > DEDUP_WINDOW_MS }
        if (previous != null && now - previous <= DEDUP_WINDOW_MS) return

        val runId = UUID.randomUUID().toString()
        runAgent(context, runId, normalizedPrompt, logger)
        activity?.finish()
        logger.info("已接管联想天禧智能体文本请求")
    }

    private fun runAgent(
        context: Context,
        runId: String,
        prompt: String,
        logger: ModuleLogger,
    ) {
        thread(name = "Eta-LenovoXiaoTian-$runId") {
            val result = runCatching {
                AgentRuntimeClient(context.applicationContext, logger).run(
                    request = AgentRuntimeWire.RunRequest(
                        runId = runId,
                        prompt = prompt,
                        config = AgentModelClient.loadConfig(),
                        images = emptyList(),
                        handoff = LenovoXiaoTianHandoff.create(runId, prompt),
                    ),
                    onEvent = {},
                )
            }.getOrElse { throwable ->
                logger.warn("联想天禧智能体 Agent 请求失败: type=${throwable.safeLogType()}")
                AgentRuntimeWire.RunResult(runId, false, "", throwable.message)
            }
            if (result.runId.isNotBlank()) {
                AgentRuntimeClient(context.applicationContext, logger).ackResult(result.runId)
            }
        }
    }

    private fun isSupportedVersion(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(
            ModuleConfig.LENOVO_XIAOTIAN_PACKAGE,
            0,
        ).longVersionCode == ModuleConfig.LENOVO_XIAOTIAN_VERSION_CODE
    }.getOrDefault(false)
}
