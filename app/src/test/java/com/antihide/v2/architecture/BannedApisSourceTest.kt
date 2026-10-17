package com.antihide.v2.architecture

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Static architecture guard. V1 turned peer addresses into MagicDNS hostnames mid-flight and lost
 * every numeric-IP admission filter, so these tokens must not exist anywhere in shipped source,
 * including the browser assets.
 */
class BannedApisSourceTest {

    private val bannedTokens = listOf(
        "remoteHost",
        "getHostName(",
        "localStorage",
        "sessionStorage",
        "?token=",
    )

    private val scannedExtensions = setOf("kt", "java", "js", "html", "css", "xml", "json", "pro")

    private fun locateSourceRoot(): File {
        val start = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        var dir: File? = start
        while (dir != null) {
            val module: File = dir
            if (File(module, "src/main").isDirectory) return File(module, "src/main")
            dir = module.parentFile
        }
        error("could not locate src/main from $start")
    }

    @Test
    @DisplayName("no shipped source references a reverse-DNS lookup, browser storage, or a URL token")
    fun productionSourcesAreFreeOfBannedApis() {
        val sourceRoot = locateSourceRoot()
        val files = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in scannedExtensions }
            .toList()

        assertTrue(files.isNotEmpty(), "no source files found under ${sourceRoot.absolutePath}")

        val violations = mutableListOf<String>()
        for (file in files) {
            val text = file.readText()
            for (token in bannedTokens) {
                if (text.contains(token)) {
                    text.lineSequence().forEachIndexed { index, line ->
                        if (line.contains(token)) {
                            violations += "${file.relativeTo(sourceRoot)}:${index + 1} banned token '$token'"
                        }
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "banned APIs found in production sources:\n" + violations.joinToString("\n"),
        )
    }

    @Test
    @DisplayName("the browser dashboard is actually present, so the scan above is meaningful")
    fun dashboardAssetsExist() {
        val dashboard = File(locateSourceRoot(), "assets/dashboard")
        assertTrue(File(dashboard, "index.html").isFile, "assets/dashboard/index.html is missing")
        assertTrue(File(dashboard, "app.js").isFile, "assets/dashboard/app.js is missing")
        assertTrue(File(dashboard, "styles.css").isFile, "assets/dashboard/styles.css is missing")
    }
}