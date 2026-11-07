package com.redline.camera.dof

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException

/** B31.1: rewrites a photo this app saved, in place (same MediaStore item, so the gallery keeps one photo). */
object MediaReplace {
    /**
     * Replaces [uri]'s bytes with [file]'s: the item is marked pending while it is written ("wt": truncated first), then
     * published again; one retry on an I/O error. A photo the user deleted meanwhile is reported, never re-created.
     */
    fun rewrite(resolver: ContentResolver, uri: Uri, file: File): DofStage.ReplaceResult {
        val t0 = SystemClock.elapsedRealtime()
        var last: Throwable? = null
        for (attempt in 0 until 2) {
            try {
                runCatching { resolver.update(uri, pending(1), null, null) }
                val os = resolver.openOutputStream(uri, "wt") ?: error("no output stream for $uri")
                os.use { o -> file.inputStream().use { it.copyTo(o) } }
                resolver.update(uri, pending(0), null, null)
                return DofStage.ReplaceResult(true, SystemClock.elapsedRealtime() - t0)
            } catch (e: FileNotFoundException) {
                return DofStage.ReplaceResult(false, SystemClock.elapsedRealtime() - t0, "photo gone (${e.message})")
            } catch (e: SecurityException) {
                return DofStage.ReplaceResult(false, SystemClock.elapsedRealtime() - t0, "not ours to rewrite (${e.message})")
            } catch (t: Throwable) {
                last = t
                runCatching { resolver.update(uri, pending(0), null, null) }
            }
        }
        return DofStage.ReplaceResult(false, SystemClock.elapsedRealtime() - t0, "${last?.javaClass?.simpleName}: ${last?.message}")
    }

    private fun pending(v: Int) = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, v) }
}
