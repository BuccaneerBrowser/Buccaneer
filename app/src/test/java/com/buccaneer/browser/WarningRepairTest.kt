package com.buccaneer.browser

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class WarningRepairTest {
    @Test fun sharedStoreIsApplicationOwnedAndStableAcrossActivities() {
        val app = RuntimeEnvironment.getApplication() as BuccaneerApplication
        val first = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            assertSame(app.bookmarks, BookmarkStore.shared(first.get()))
            val second = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
            try { assertSame(BookmarkStore.shared(first.get()), BookmarkStore.shared(second.get())) }
            finally { second.pause().stop().destroy() }
            assertFalse(BookmarkStore::class.java.declaredFields.any { Context::class.java.isAssignableFrom(it.type) })
            assertFalse(BookmarkStore.Companion::class.java.declaredFields.any { Context::class.java.isAssignableFrom(it.type) })
        } finally { first.pause().stop().destroy() }
    }

    @Test fun allCustomViewsExposeUsableContextConstructors() {
        val app = RuntimeEnvironment.getApplication()
        for (type in listOf(CursorView::class.java,Keypad::class.java,Osd::class.java,MenuPanel::class.java,ListPanel::class.java,TextPanel::class.java,StartPage::class.java,BarView::class.java)) {
            assertNotNull(type.getConstructor(Context::class.java).newInstance(app))
        }
    }

    @Test
    @Config(sdk=[22], shadows=[Regression171Test.ScriptWebView::class])
    fun minimumSdk22StillStartsAndUsesApplicationOwnedBookmarks() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            val app = activity.application as BuccaneerApplication
            assertSame(app.bookmarks, BookmarkStore.shared(activity))
            val start = MainActivity::class.java.getDeclaredField("startPage").apply { isAccessible = true }.get(activity) as StartPage
            assertTrue(start.shown())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun previewFormattingPreservesLiteralInputAndCaret() {
        val app = RuntimeEnvironment.getApplication()
        for (text in listOf("", "100%", "%1\$s", "a\nb", "\uD83D\uDE80", "|")) {
            assertEquals(text + "|", app.getString(R.string.keyboard_preview, text))
        }
    }
}
