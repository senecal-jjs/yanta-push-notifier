package com.yantagram.push

enum class Platform(val wire: String) {
    IOS("ios"),
    ANDROID("android");

    companion object {
        fun fromWire(value: String): Platform? = entries.firstOrNull { it.wire == value }
    }
}
