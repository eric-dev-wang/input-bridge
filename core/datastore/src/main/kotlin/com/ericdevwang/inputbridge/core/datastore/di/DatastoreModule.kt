package com.ericdevwang.inputbridge.core.datastore.di

import com.ericdevwang.inputbridge.core.datastore.internal.DataStoreTextDataSource
import com.ericdevwang.inputbridge.core.datastore.TextDataSource
import com.ericdevwang.inputbridge.core.datastore.internal.inputBridgeDataStore
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val datastoreModule = module {
    single<TextDataSource> {
        DataStoreTextDataSource(androidContext().inputBridgeDataStore)
    }
}
