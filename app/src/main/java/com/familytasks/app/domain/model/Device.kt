package com.familytasks.app.domain.model

data class Device(
    val deviceId: String,
    val displayName: String,
    val color: Int,
    val lastSeenAt: Long? = null
)
