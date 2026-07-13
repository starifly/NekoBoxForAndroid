package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ShortcutTargetPackageTest {

    @Test
    fun shortcutIntents_targetCurrentApplicationVariant() {
        val application = RuntimeEnvironment.getApplication()
        val resources = application.resources
        val shortcutIds = mutableListOf<String>()
        var intentCount = 0
        val parser = resources.getXml(R.xml.shortcuts)

        try {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "shortcut" -> {
                            val shortcutId =
                                parser.getAttributeValue(ANDROID_NAMESPACE, "shortcutId").orEmpty()
                            shortcutIds += shortcutId
                        }

                        "intent" -> {
                            intentCount++
                            assertEquals(
                                application.packageName,
                                parser.getAttributeValue(ANDROID_NAMESPACE, "targetPackage"),
                            )
                            assertEquals(
                                0,
                                parser.getAttributeResourceValue(
                                    ANDROID_NAMESPACE,
                                    "targetPackage",
                                    0,
                                ),
                            )
                            assertTrue(
                                parser.getAttributeValue(ANDROID_NAMESPACE, "targetClass")
                                    .orEmpty()
                                    .isNotBlank(),
                            )
                        }
                    }
                }
                parser.next()
            }
        } finally {
            parser.close()
        }

        assertEquals(4, intentCount)
        assertEquals(4, shortcutIds.size)
        assertEquals(setOf("toggle", "enable", "disable", "scan"), shortcutIds.toSet())
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
