package dev.phonepal.app.ui

import dev.phonepal.app.session.ChatItem

/** Fold only adjacent diagnostic entries. Questions, failures and replies always remain visible. */
internal sealed class TimelineRow {
    abstract val id: Long
    data class Message(val item: ChatItem) : TimelineRow() { override val id = item.id }
    data class Process(val entries: List<ChatItem.Progress>) : TimelineRow() { override val id = entries.first().id }
}

internal fun chatTimeline(items: List<ChatItem>): List<TimelineRow> = buildList {
    val pending = mutableListOf<ChatItem.Progress>()
    fun flush() {
        if (pending.isNotEmpty()) { add(TimelineRow.Process(pending.toList())); pending.clear() }
    }
    items.forEach { item ->
        if (item is ChatItem.Progress) pending += item else { flush(); add(TimelineRow.Message(item)) }
    }
    flush()
}
