package com.xmelon.rubik_solver

actual fun platformLog(tag: String, msg: String) {
    android.util.Log.i(tag, msg)
}
