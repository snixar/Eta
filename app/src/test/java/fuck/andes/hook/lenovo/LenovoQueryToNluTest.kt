package fuck.andes.hook.lenovo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LenovoQueryToNluTest {
    private val booleanType: Class<*> = Boolean::class.javaPrimitiveType!!

    private fun signature(vararg types: Class<*>): Array<Class<*>> = arrayOf(*types)

    // 设备实测的两个语音重载（文本位于 args[1]）。
    private val fiveArgVoice = signature(
        Any::class.java, // Lzk; 联想内部上下文
        String::class.java,
        String::class.java,
        booleanType,
        booleanType,
    )

    private val sixArgVoice = signature(
        Any::class.java,
        String::class.java,
        String::class.java,
        booleanType,
        booleanType,
        String::class.java,
    )

    @Test
    fun acceptsBothDocumentedVoiceOverloads() {
        assertTrue(LenovoXiaoTianQueryToNlu.isCandidate("queryToNlu", fiveArgVoice))
        assertTrue(LenovoXiaoTianQueryToNlu.isCandidate("queryToNlu", sixArgVoice))
    }

    @Test
    fun rejectsUnrelatedNames() {
        assertFalse(LenovoXiaoTianQueryToNlu.isCandidate("query2Nlu", fiveArgVoice))
        assertFalse(LenovoXiaoTianQueryToNlu.isCandidate("queryNlu", fiveArgVoice))
        assertFalse(LenovoXiaoTianQueryToNlu.isCandidate("", fiveArgVoice))
    }

    @Test
    fun rejectsWrongParameterShapes() {
        assertFalse(LenovoXiaoTianQueryToNlu.isCandidate("queryToNlu", signature(String::class.java)))
        assertFalse(
            LenovoXiaoTianQueryToNlu.isCandidate(
                "queryToNlu",
                signature(
                    Any::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType!!, // args[2] 应为 String
                    booleanType,
                    booleanType,
                ),
            ),
        )
        assertFalse(
            LenovoXiaoTianQueryToNlu.isCandidate(
                "queryToNlu",
                signature(
                    Any::class.java,
                    String::class.java,
                    String::class.java,
                    booleanType,
                    booleanType,
                    String::class.java,
                    String::class.java, // 7 参不在范围内
                ),
            ),
        )
        assertFalse(
            LenovoXiaoTianQueryToNlu.isCandidate(
                "queryToNlu",
                signature(
                    Any::class.java,
                    String::class.java,
                    String::class.java,
                    booleanType,
                    Int::class.javaPrimitiveType!!, // args[4] 应为 boolean
                ),
            ),
        )
    }

    @Test
    fun extractsPromptFromArgumentOne() {
        assertEquals(
            "今天天气怎么样",
            LenovoXiaoTianQueryToNlu.extractPrompt(arrayOf(Any(), " 今天天气怎么样 ", "ctx")),
        )
    }

    @Test
    fun returnsNullForBlankOrWrongTypes() {
        assertNull(LenovoXiaoTianQueryToNlu.extractPrompt(arrayOf(Any(), "   ", "ctx")))
        assertNull(LenovoXiaoTianQueryToNlu.extractPrompt(arrayOf(Any(), "", "ctx")))
        assertNull(LenovoXiaoTianQueryToNlu.extractPrompt(arrayOf(Any(), 42, "ctx")))
        assertNull(LenovoXiaoTianQueryToNlu.extractPrompt(emptyArray()))
        assertNull(LenovoXiaoTianQueryToNlu.extractPrompt(arrayOf(Any())))
    }
}
