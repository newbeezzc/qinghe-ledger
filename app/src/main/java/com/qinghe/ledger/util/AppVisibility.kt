package com.qinghe.ledger.util

/**
 * Set by MainActivity. The accessibility service can skip expensive tree
 * traversal while the bookkeeping UI is visible, keeping list scrolling smooth.
 */
object AppVisibility {
    @Volatile
    var isForeground: Boolean = false
}
