package com.buccaneer.browser

import android.app.Application

/** Application-scoped store survives Activity recreation without static Context roots. */
class BuccaneerApplication : Application(), BookmarkProvider {
    override val bookmarks: BookmarkStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { BookmarkStore(this) }
}
