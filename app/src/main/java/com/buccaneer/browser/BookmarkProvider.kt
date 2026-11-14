package com.buccaneer.browser

/** Application capability, independent of the concrete Android Application class. */
interface BookmarkProvider { val bookmarks: BookmarkStore }

/** Composition-root lookup stays outside the store so the storage layer has no upward dependency. */
fun sharedBookmarks(context: android.content.Context): BookmarkStore =
    (context.applicationContext as BookmarkProvider).bookmarks
