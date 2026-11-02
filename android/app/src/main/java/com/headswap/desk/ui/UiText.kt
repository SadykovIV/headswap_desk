package com.headswap.desk.ui

import android.content.Context

/**
 * Текст для пользователя без Context: ресурс + аргументы формата, либо готовая строка (например, сообщение исключения).
 * Чистая логика (HomeLogic, Readiness) отдаёт UiText, в строку его превращает UI или уведомление через [get].
 */
sealed class UiText {
    data class Res(val id: Int, val args: List<Any> = emptyList()) : UiText()

    data class Plural(val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText()

    data class Raw(val text: String) : UiText()

    fun get(ctx: Context): String = when (this) {
        is Res -> if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args.map { it.resolve(ctx) }.toTypedArray())
        is Plural -> ctx.resources.getQuantityString(id, count, *args.map { it.resolve(ctx) }.toTypedArray())
        is Raw -> text
    }

    companion object {
        fun res(id: Int, vararg args: Any): UiText = Res(id, args.toList())

        fun raw(text: String): UiText = Raw(text)

        /** Аргумент может сам быть UiText: резолвится тем же контекстом. */
        private fun Any.resolve(ctx: Context): Any = if (this is UiText) get(ctx) else this
    }
}
