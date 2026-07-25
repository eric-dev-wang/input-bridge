package com.ericdevwang.inputbridge.core.datastore.internal

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

internal val Context.inputBridgeDataStore by preferencesDataStore(name = "input_bridge")

internal val TEXT_KEY = stringPreferencesKey("text")
internal val VERSION_KEY = longPreferencesKey("version")
internal val UPDATED_AT_KEY = longPreferencesKey("updated_at")
