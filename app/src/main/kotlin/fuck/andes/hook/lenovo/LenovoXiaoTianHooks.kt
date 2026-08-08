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

/**
 * `WithAIBiz.queryToNlu` 语音文本入口的结构判定与参数提取。
 * 独立于 Xposed，便于在 JVM 单元测试中直接验证。
 *
 * 设备实测（2026-08-07）语音文本经该内部方法进入 NLU，而非 Intent 的 `query` extra：
 * - 5 参重载 `(Lzk;String;String;ZZ)V`
 * - 6 参重载 `(Lzk;String;String;ZZString;)V`
 * 文本位于 args[1]，`Lzk;` 是联想内部上下文类型，不硬编码其类名。
 */
internal object LenovoXiaoTianQueryToNlu {
    private const val PROMPT_ARG_INDEX = 1
    private const val MIN_ARGS = 5
    private const val MAX_ARGS = 6

    fun isCandidate(name: String, parameterTypes: Array<Class<*>>): Boolean {
        if (name != ModuleConfig.LENOVO_XIAOTIAN_QUERY_TO_NLU_METHOD) return false
        if (parameterTypes.size !in MIN_ARGS..MAX_ARGS) return false
        return parameterTypes[1] == String::class.java &&
            parameterTypes[2] == String::class.java &&
            parameterTypes[3] == Boolean::class.javaPrimitiveType &&
            parameterTypes[4] == Boolean::class.javaPrimitiveType
    }

    fun extractPrompt(args: Array<out Any?>): String? =
        (args.getOrNull(PROMPT_ARG_INDEX) as? String)
            ?.trim()
            ?.takeIf(String::isNotBlank)
}

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
            installActivityTextEntry(classLoader)
            installQueryToNluVoiceEntry(classLoader)
        }
    }

    private fun HookRegistrar.installActivityTextEntry(classLoader: ClassLoader) {
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
            return
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

    /**
     * 语音文本入口：拦截 `WithAIBiz.queryToNlu` 的全部候选重载。
     * 接管时吞掉原生 NLU 处理（不 proceed），由 Eta 浮层展示结果；
     * 前置条件不满足时放行原生链路。与文本入口共享去重窗口，避免双跑。
     */
    private fun HookRegistrar.installQueryToNluVoiceEntry(classLoader: ClassLoader) {
        val bizClass = HookSupport.findClassOrNull(
            classLoader,
            ModuleConfig.LENOVO_XIAOTIAN_BIZ_CLASS,
        )
        if (bizClass == null) {
            missing(
                id = "lenovo.query-to-nlu",
                description = "天禧智能体 WithAIBiz 语音入口",
                detail = "未找到联想天禧智能体 WithAIBiz 类",
            )
            return
        }
        val methods = HookSupport.findDeclaredMethods(bizClass) { method ->
            LenovoXiaoTianQueryToNlu.isCandidate(method.name, method.parameterTypes)
        }
        if (methods.isEmpty()) {
            missing(
                id = "lenovo.query-to-nlu",
                description = "天禧智能体 queryToNlu 语音入口",
                detail = "未找到联想天禧智能体 WithAIBiz.queryToNlu 候选重载",
            )
            return
        }
        methods.forEachIndexed { index, method ->
            intercept(
                id = "lenovo.query-to-nlu-$index",
                executable = method,
                description = "天禧智能体 WithAIBiz.queryToNlu",
            ) { chain ->
                val prompt = LenovoXiaoTianQueryToNlu.extractPrompt(chain.args)
                if (prompt == null) return@intercept chain.proceed()
                val context = AgentAppContext.resolve()
                if (context == null || !maybeRunAgent(context, prompt, logger)) {
                    return@intercept chain.proceed()
                }
                // 只记录长度，不记录语音文本（遵守日志裁剪策略）。
                logger.info("已接管联想天禧智能体语音文本请求, 文本长度=${prompt.length}")
                null
            }
        }
    }

    private fun handleIntent(
        activity: Activity?,
        intent: Intent?,
        logger: ModuleLogger,
    ) {
        val context = activity ?: AgentAppContext.resolve() ?: return
        val prompt = intent
            ?.getStringExtra(ModuleConfig.LENOVO_XIAOTIAN_QUERY_EXTRA)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return
        if (maybeRunAgent(context, prompt, logger)) {
            activity?.finish()
            logger.info("已接管联想天禧智能体文本请求")
        }
    }

    /**
     * 统一前置检查 + 去重 + 启动 Agent。返回 true 表示本轮已接管（调用方负责吞掉原生处理）。
     */
    private fun maybeRunAgent(
        context: Context,
        prompt: String,
        logger: ModuleLogger,
    ): Boolean {
        if (!isSupportedVersion(context)) return false
        if (!Prefs.isEnabled(Prefs.Keys.AGENT_CUSTOM_MODEL)) return false
        if (Prefs.isEnabled(Prefs.Keys.AGENT_REQUIRE_PREFIX) && !prompt.startsWith("/agent ")) {
            return false
        }
        val normalizedPrompt = prompt
            .removePrefix("/agent ")
            .trim()
            .take(MAX_QUERY_CHARS)
            .takeIf(String::isNotBlank)
            ?: return false
        val dedupKey = "${context.packageName}:$normalizedPrompt"
        val now = System.currentTimeMillis()
        val previous = recentQueries.put(dedupKey, now)
        recentQueries.entries.removeIf { now - it.value > DEDUP_WINDOW_MS }
        if (previous != null && now - previous <= DEDUP_WINDOW_MS) return false

        val runId = UUID.randomUUID().toString()
        runAgent(context, runId, normalizedPrompt, logger)
        return true
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
