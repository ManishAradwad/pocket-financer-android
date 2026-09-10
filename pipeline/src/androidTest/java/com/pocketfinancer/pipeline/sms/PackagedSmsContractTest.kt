package com.pocketfinancer.pipeline.sms

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Verify APK bytes, since JVM-only tests cannot catch Windows asset packaging drift. */
@RunWith(AndroidJUnit4::class)
class PackagedSmsContractTest {
    @Test fun pinnedAssetsRetainReleaseBytes() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val expected = mapOf(
            "currency-v1.json" to "cb5d991a5ade283f6b2406e4427a2fd1bf5468f67ab6f573f6eb97b4c5919c78",
            "selector-prompt-v1.txt" to "3da47ec16b074ddcf2c03526dd968f9f1f668f9bfbed69bc01c4e2b5c613b940",
            "profile-core-en-v1.json" to "c9f7b95ce70528d4b564458e320a8788fdf2076ffb55d1d80e80687c48d48b52",
            "profile-india-v1.json" to "1cb1a4d7431bb9fe141aa90d45bb3820a115942123668eb4efd6522a73ed0156"
        )
        expected.forEach { (name, digest) ->
            val bytes = assets.open("sms_processing/$name").use { it.readBytes() }
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            assertEquals("Frozen asset changed during checkout or packaging: $name", digest, actual)
        }
    }
}
