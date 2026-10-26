package com.qinghe.ledger.data

import com.qinghe.ledger.data.local.CategoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryOrderingTest {

    private fun child(id: Long, name: String, sortOrder: Int) =
        CategoryEntity(
            id = id,
            name = name,
            emoji = "🍜",
            sortOrder = sortOrder,
            parentId = 1L
        )

    @Test
    fun `other stays last when it was inserted before a new subcategory`() {
        val sorted = listOf(
            child(1, "早餐", 0),
            child(2, "其他", 1),
            child(3, "酒水饮料", 2)
        ).sortedKeepingOtherLast()

        assertEquals(listOf("早餐", "酒水饮料", "其他"), sorted.map { it.name })
    }

    @Test
    fun `other stays last after it was first in storage order`() {
        val sorted = listOf(
            child(1, "其他", 0),
            child(2, "午餐", 1),
            child(3, "晚餐", 2)
        ).sortedKeepingOtherLast()

        assertEquals(listOf("午餐", "晚餐", "其他"), sorted.map { it.name })
    }

    @Test
    fun `sortOrder is preserved for non-other rows`() {
        val sorted = listOf(
            child(1, "晚餐", 5),
            child(2, "早餐", 2),
            child(3, "其他", 0)
        ).sortedKeepingOtherLast()

        assertEquals(listOf("早餐", "晚餐", "其他"), sorted.map { it.name })
    }
}
