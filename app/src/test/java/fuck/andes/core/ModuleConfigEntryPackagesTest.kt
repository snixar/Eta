package fuck.andes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleConfigEntryPackagesTest {
    @Test
    fun runtimeOnlyTrustsTheKnownAssistantEntryPackages() {
        assertEquals(
            setOf(
                "com.heytap.speechassist",
                "com.miui.voiceassist",
                "com.lenovo.menu_assistant.hd",
            ),
            ModuleConfig.AGENT_RUNTIME_ENTRY_PACKAGES,
        )
        assertTrue(ModuleConfig.XIAOAI_CORE_PROCESS.endsWith(":core"))
    }
}
