package com.secondscreen.app.privileged

data class PrivResult(val ok: Boolean, val message: String)

internal fun Throwable.describe(): String = "${javaClass.simpleName}: $message"
