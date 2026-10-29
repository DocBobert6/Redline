package com.redline.camera.nativebridge

import com.redline.camera.look.CubeLut

/**
 * JNI wrapper for libredline_native.so (B20, app/src/main/cpp). The library is loaded lazily and at most once;
 * a failed load is remembered with its reason (the look preview is then disabled and the report says why) and
 * never crashes the app.
 */
object RedlineNative {
    const val LIBRARY = "redline_native"

    /** Floats ahead of the LUT data in [nativeParseCube]'s result: size, domain min (3), domain max (3). */
    const val HEADER_FLOATS = 7

    data class LoadResult(val loaded: Boolean, val version: String?, val error: String?)

    @Volatile
    private var result: LoadResult? = null

    /** Loads the library on first use; later calls return the remembered outcome. Thread-safe. */
    fun load(): LoadResult = result ?: synchronized(this) {
        result ?: attempt { System.loadLibrary(it) }.also { result = it }
    }

    /** One load attempt through [loader]; never throws. Not cached (tests call it directly). */
    internal fun attempt(loader: (String) -> Unit): LoadResult = try {
        loader(LIBRARY)
        LoadResult(loaded = true, version = nativeVersion(), error = null)
    } catch (e: LinkageError) {
        LoadResult(loaded = false, version = null, error = "${e.javaClass.simpleName}: ${e.message}")
    } catch (e: RuntimeException) {
        LoadResult(loaded = false, version = null, error = "${e.javaClass.simpleName}: ${e.message}")
    }

    /** Parses .cube text natively. Throws IllegalStateException when the library is not loaded, IllegalArgumentException on bad data. */
    fun parseCube(bytes: ByteArray): CubeLut {
        val loaded = load()
        check(loaded.loaded) { "native library not loaded: ${loaded.error}" }
        return CubeLut.fromNativeLayout(nativeParseCube(bytes))
    }

    @JvmStatic
    private external fun nativeVersion(): String

    @JvmStatic
    private external fun nativeParseCube(bytes: ByteArray): FloatArray
}
