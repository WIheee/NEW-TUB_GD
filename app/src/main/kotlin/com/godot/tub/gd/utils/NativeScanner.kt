package com.godot.tub.gd.utils

import java.io.File

/**
 * 原生文件遍历（C++），只负责「列出文件相对路径」，
 * 内容读取与格式化仍由 Kotlin 负责。
 */
object NativeScanner {

    private val libraryLoaded: Boolean by lazy {
        runCatching { System.loadLibrary("tubgd") }.isSuccess
    }

    private external fun nativeListRelativePaths(
        rootPath: String,
        hardExcludeDirs: Array<String>
    ): Array<String>?

    data class NativeFile(val relativePath: String, val size: Long)

    /**
     * C++ 返回格式："<size>\u0001<relativePath>"。
     * @return 文件列表；原生库不可用或出错时返回 null，调用方回退到 SAF 扫描。
     */
    fun listFiles(root: File, hardExcludeDirs: Set<String>): List<NativeFile>? {
        if (!libraryLoaded) return null
        return try {
            nativeListRelativePaths(root.absolutePath, hardExcludeDirs.toTypedArray())?.mapNotNull { raw ->
                val separator = raw.indexOf('\u0001')
                if (separator <= 0) {
                    NativeFile(raw, -1L)
                } else {
                    val size = raw.substring(0, separator).toLongOrNull() ?: -1L
                    NativeFile(raw.substring(separator + 1), size)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }
}
