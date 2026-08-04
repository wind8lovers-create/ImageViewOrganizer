package com.hazuki.imageorganizer.data

enum class RecentEntryType { FOLDER, ZIP }

data class RecentEntry(
    val type: RecentEntryType,
    val uri: String,
    val label: String,
    val timestampMillis: Long
)
