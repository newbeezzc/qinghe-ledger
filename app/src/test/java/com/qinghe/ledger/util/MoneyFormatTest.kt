package com.qinghe.ledger.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyFormatTest {

    @Test
    fun `compact keeps small amounts exact`() {
        assertEquals("¥0.00", MoneyFormat.compactWithSymbol(0))
        assertEquals("¥999.99", MoneyFormat.compactWithSymbol(99_999))
    }

    @Test
    fun `compact uses wan`() {
        assertEquals("¥1万", MoneyFormat.compactWithSymbol(1_000_000))
        assertEquals("¥1.2万", MoneyFormat.compactWithSymbol(1_234_567))
    }

    @Test
    fun `compact keeps a negative sign`() {
        assertEquals("-¥1.2万", MoneyFormat.compactWithSymbol(-1_234_567))
    }

    @Test
    fun `compact uses yi for very large amounts`() {
        assertEquals("¥1亿", MoneyFormat.compactWithSymbol(10_000_000_000L))
    }
}
