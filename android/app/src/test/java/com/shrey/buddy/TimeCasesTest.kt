package com.shrey.buddy

import com.shrey.buddy.data.tasks.QuickParse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/** The same sentences the laptop is tested with (shared/time-cases.json): both must read times alike. */
class TimeCasesTest {
    @Test fun `every shared time case`() {
        val file = listOf(File("../../shared/time-cases.json"), File("../shared/time-cases.json"), File("shared/time-cases.json")).first { it.exists() }
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val now = LocalDateTime.parse(root.getValue("now").jsonPrimitive.content)
        val failures = mutableListOf<String>()
        for (c in root.getValue("cases").jsonArray) {
            val a = c as JsonArray
            val text = a[0].jsonPrimitive.content
            val title = a[1].jsonPrimitive.content
            val date = a[2].takeUnless { it is JsonNull }?.jsonPrimitive?.content
            val time = a[3].takeUnless { it is JsonNull }?.jsonPrimitive?.content
            val r = QuickParse.parse(text, now)
            val got = listOf(r.title, r.date?.toString(), r.time?.toString())
            val want = listOf(title, date, time)
            if (got != want) failures += "\"$text\": want $want, got $got"
        }
        assertTrue(failures.joinToString("\n", prefix = "${failures.size} failing:\n"), failures.isEmpty())
        assertEquals(true, true)
    }
}
