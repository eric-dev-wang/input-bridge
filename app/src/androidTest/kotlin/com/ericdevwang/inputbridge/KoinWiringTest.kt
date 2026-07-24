package com.ericdevwang.inputbridge

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ericdevwang.inputbridge.core.data.repository.DefaultTextRepository
import com.ericdevwang.inputbridge.core.data.repository.TextRepository
import com.ericdevwang.inputbridge.server.InputBridgeServer
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named

@RunWith(AndroidJUnit4::class)
class KoinWiringTest {
    @Test
    fun applicationStartsKoinWithSingletonTextRepository() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val koin = GlobalContext.get()

        val firstRepository = koin.get<TextRepository>()
        val secondRepository = koin.get<TextRepository>()

        assertTrue(application is InputBridgeApplication)
        assertTrue(firstRepository is DefaultTextRepository)
        assertSame(firstRepository, secondRepository)

        assertTrue(koin.get<InputBridgeServer>() === koin.get<InputBridgeServer>())

        val expectedVersion = application.packageManager
            .getPackageInfo(application.packageName, 0)
            .versionName
        assertEquals(expectedVersion, koin.get<String>(named("appVersion")))
    }
}
