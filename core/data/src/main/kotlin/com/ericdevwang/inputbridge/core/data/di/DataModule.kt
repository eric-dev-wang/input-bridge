package com.ericdevwang.inputbridge.core.data.di

import com.ericdevwang.inputbridge.core.data.internal.repository.DefaultTextRepository
import com.ericdevwang.inputbridge.core.data.repository.TextRepository
import com.ericdevwang.inputbridge.core.datastore.di.datastoreModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.module

private const val DATA_REPOSITORY_SCOPE = "dataRepositoryScope"

val dataModule = module {
    includes(datastoreModule)

    single<CoroutineScope>(named(DATA_REPOSITORY_SCOPE)) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    single<TextRepository> {
        DefaultTextRepository(
            dataSource = get(),
            scope = get(named(DATA_REPOSITORY_SCOPE)),
        )
    }
}
