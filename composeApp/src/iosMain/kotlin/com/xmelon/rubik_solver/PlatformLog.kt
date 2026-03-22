package com.xmelon.rubik_solver

import platform.Foundation.NSLog

actual fun platformLog(tag: String, msg: String) {
    NSLog("$tag: $msg")
}
