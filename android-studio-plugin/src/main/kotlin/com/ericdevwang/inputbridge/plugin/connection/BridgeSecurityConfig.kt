package com.ericdevwang.inputbridge.plugin.connection

import java.nio.charset.StandardCharsets

internal object BridgeSecurityConfig {
    val sharedSecret: String by lazy {
        BridgeSecurityConfig::class.java
            .getResourceAsStream("/input-bridge-shared-secret.txt")
            ?.readBytes()
            ?.toString(StandardCharsets.UTF_8)
            ?: error("The Input Bridge shared secret was not packaged into the plugin.")
    }
}
