package com.qinghe.ledger.data.model

enum class TransactionType {
    EXPENSE,
    INCOME,
    TRANSFER;

    companion object {
        fun from(raw: String?): TransactionType =
            entries.firstOrNull { it.name == raw } ?: EXPENSE
    }
}

enum class TransactionSource {
    MANUAL,
    NOTIFICATION,
    ACCESSIBILITY;

    companion object {
        fun from(raw: String?): TransactionSource =
            entries.firstOrNull { it.name == raw } ?: MANUAL
    }
}

enum class AccountType(val label: String, val emoji: String) {
    CASH("现金", "💵"),
    WECHAT("微信", "💬"),
    ALIPAY("支付宝", "🐜"),
    DOUYIN("抖音", "🎵"),
    MEITUAN("美团", "🟡"),
    BANK("银行卡", "🏦"),
    CREDIT("信用卡", "💳"),
    OTHER("其他", "📦");

    companion object {
        fun from(raw: String?): AccountType =
            entries.firstOrNull { it.name == raw } ?: OTHER
    }
}
