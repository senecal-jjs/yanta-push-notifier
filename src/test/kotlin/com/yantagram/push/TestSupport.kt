package com.yantagram.push

import com.yantagram.push.registry.Registry
import com.yantagram.push.registry.Store
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files

fun tempDbPath(): String = Files.createTempDirectory("push-test").resolve("push.db").toString()

fun newRegistry(path: String = tempDbPath(), max: Int = 200_000) =
    Registry(Store(path), max, Dispatchers.Unconfined)

fun tag(n: Int): String = n.toString(16).padStart(32, '0')

fun apnsToken(n: Int): String = n.toString(16).padStart(64, 'a')

fun fcmToken(n: Int): String = ("fcm$n:" + "A".repeat(150))
