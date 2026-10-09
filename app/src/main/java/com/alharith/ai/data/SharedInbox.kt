package com.alharith.ai.data

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** ملف أو نص شاركه المستخدم مع رفيق، يُرفق تلقائيًا مع الطلب التالي. */
object SharedInbox {
    data class Item(val uri: Uri?, val text: String?, val label: String)

    private val _item = MutableStateFlow<Item?>(null)
    val item: StateFlow<Item?> = _item.asStateFlow()

    fun set(i: Item?) { _item.value = i }
    fun take(): Item? = _item.value.also { _item.value = null }
}
