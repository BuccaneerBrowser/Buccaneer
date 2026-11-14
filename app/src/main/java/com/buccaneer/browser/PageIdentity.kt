package com.buccaneer.browser

/** Read-only capability passed to page subsystems. No controller can rotate another's document. */
interface DocumentIdentity {
    val epoch: Long
    val token: String
    fun snapshot(): PageStamp = PageStamp(epoch, token)
    fun matches(stamp: PageStamp): Boolean = epoch == stamp.epoch && token == stamp.token
}
data class PageStamp(val epoch: Long, val token: String)

/** Lifecycle/navigation owns transitions; JavaBridge readers see one immutable publication. */
class PageIdentity : DocumentIdentity {
    @Volatile private var current = PageStamp(0L, java.util.UUID.randomUUID().toString())
    override val epoch get() = current.epoch
    override val token get() = current.token
    override fun snapshot() = current
    override fun matches(stamp: PageStamp) = current == stamp
    fun advance() { current = current.copy(epoch=current.epoch+1) }
    fun rotateDocument() { current = current.copy(token=java.util.UUID.randomUUID().toString()) }
}
