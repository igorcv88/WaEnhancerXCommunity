@file:JvmName("DexKitClassQuery")

package com.waenhancer.xposed.core.devkit

import org.luckypray.dexkit.query.FindClass

/** DexKit exposes the early-exit switch to Kotlin only. */
fun enableFirstMatch(query: FindClass, enabled: Boolean) {
    query.findFirst = enabled
}
