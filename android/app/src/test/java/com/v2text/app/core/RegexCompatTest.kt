package com.v2text.app.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android compiles regexes with ICU, not the JVM engine these tests run on. ICU rejects the
 * (?U) / UNICODE_CHARACTER_CLASS flag, which made TextProcessor fail to load on a real phone
 * (0.1.0 crash, 0.1.2 "NoClassDefFoundError"). This test
 *  1. fails if any app source uses a flag ICU does not support, and
 *  2. writes every pattern TextProcessor compiles to build/regex-patterns.txt, which
 *     tools/icu_regex_check.py compiles with real ICU (run in CI / before releases).
 */
class RegexCompatTest {
    private val unsupported = listOf("(?U", "UNICODE_CHARACTER_CLASS", "(?u", "\\p{IsAlphabetic}", "\\p{javaLowerCase}")

    @Test fun noIcuUnsupportedFlagsInAppSources() {
        val src = File("src/main/java")
        assertTrue("run from the app module directory", src.isDirectory)
        val offenders = src.walk().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                unsupported.firstOrNull { line.contains(it) && !line.trimStart().startsWith("//") }
                    ?.let { "${f.name}:${i + 1} uses $it" }
            }
        }.toList()
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test fun allFormatterPatternsAreExportedForIcu() {
        val pats = TextProcessor.patternsForCompatCheck()
        assertTrue(pats.size >= 15)
        pats.forEach { p -> unsupported.forEach { assertTrue("$p uses $it", !p.contains(it)) } }
        File("build").mkdirs()
        File("build/regex-patterns.txt").writeText(pats.joinToString("\n"))
    }
}
