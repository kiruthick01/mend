package com.mend.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class EngineTest {

    @Test
    fun `engine module is present and testable without android`() {
        assertEquals("0.1.0", Engine.VERSION)
    }
}
