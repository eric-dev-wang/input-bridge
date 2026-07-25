package com.ericdevwang.inputbridge.core.data.repository

sealed interface PersistenceResult {
    data class Succeeded(val version: Long) : PersistenceResult

    data class Failed(val version: Long) : PersistenceResult

    data class Superseded(val version: Long) : PersistenceResult
}
