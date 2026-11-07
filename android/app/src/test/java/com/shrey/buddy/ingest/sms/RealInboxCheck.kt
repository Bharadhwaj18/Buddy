package com.shrey.buddy.ingest.sms

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Developer check against a local dump of real bank SMS ("sender<TAB>body" per line).
 * The dump is personal data: it lives outside the repo and this test is skipped unless
 * BUDDY_SMS_DUMP points at it.
 */
class RealInboxCheck {
    @Test fun `report parse coverage for a real SMS dump`() {
        val path = System.getenv("BUDDY_SMS_DUMP")
        assumeTrue("BUDDY_SMS_DUMP not set", path != null && File(path).exists())

        val results = File(path!!).readLines().filter { it.contains('\t') }.map { line ->
            val (sender, body) = line.split('\t', limit = 2)
            Triple(sender, body, SmsParsers.parse(sender, body))
        }
        val byType = results.groupingBy { it.third?.let { p -> p::class.simpleName } ?: "null" }.eachCount()
        println("TOTAL ${results.size}: $byType")
        results.filter { it.third is SmsParse.Unrecognised }.forEach { (s, b, _) -> println("UNRECOGNISED $s | $b") }
    }
}
