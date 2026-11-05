package dev.pocketengine.ui

internal fun sshChildPath(directory: String, name: String): String? {
    if (name.isBlank() || name in setOf(".", "..") || name.contains('/') || name.contains('\\') || name.any { it.code < 32 || it.code == 127 }) return null
    if (directory.startsWith('/') || directory.contains('\\') || directory.split('/').any { it == "." || it == ".." }) return null
    return if (directory.isBlank()) name else directory + "/" + name
}
