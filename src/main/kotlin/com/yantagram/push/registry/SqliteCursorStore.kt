package com.yantagram.push.registry

import com.yantagram.push.relay.CursorStore

class SqliteCursorStore(private val store: Store) : CursorStore {
    override fun load(): Long? = store.getMeta(KEY)?.toLongOrNull()

    override fun save(cursor: Long) = store.setMeta(KEY, cursor.toString())

    private companion object {
        const val KEY = "relay_cursor"
    }
}
