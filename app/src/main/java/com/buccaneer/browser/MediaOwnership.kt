package com.buccaneer.browser

interface MediaOwnershipView { val owner: String; val videoActive: Boolean }

/** Only media reports/navigation change video state; no editor/input capability is accepted. */
class MediaOwnership(private val document: DocumentIdentity) : MediaOwnershipView {
    @Volatile override var owner = ""; private set
    @Volatile override var videoActive = false; private set
    private var acceptingCommands = false
    fun setForeground(active: Boolean) { acceptingCommands = active }
    fun started(id: String): Boolean {
        if (!acceptingCommands || !id.startsWith(document.token+":")) return false
        owner=id.take(240);videoActive=true;return true
    }
    fun stopped(id: String) { if (id.startsWith(document.token+":") && owner==id) owner="" }
    fun markInactive() { videoActive=false }
    fun reset() { owner="" }
}
