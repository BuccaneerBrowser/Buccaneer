package com.buccaneer.browser

interface ForegroundView { val foreground: Boolean }
/** Activity lifecycle is the sole writer. Controllers get independent enable/disable commands. */
class ForegroundState : ForegroundView {
    @Volatile override var foreground=false; private set
    fun resume() { foreground=true }
    fun pause() { foreground=false }
}
