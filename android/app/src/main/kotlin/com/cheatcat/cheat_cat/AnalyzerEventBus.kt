package com.cheatcat.cheat_cat

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

object AnalyzerEventBus {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sink: EventChannel.EventSink? = null
    private var lastEvent: Map<String, Any> = mapOf("status" to "idle")

    fun attach(eventSink: EventChannel.EventSink) {
        sink = eventSink
        mainHandler.post { sink?.success(lastEvent) }
    }

    fun detach() {
        sink = null
    }

    fun emit(event: Map<String, Any>) {
        lastEvent = event
        mainHandler.post { sink?.success(event) }
    }
}
