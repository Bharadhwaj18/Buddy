package com.shrey.buddy

import com.shrey.buddy.ui.screens.maskNumbers
import org.junit.Assert.assertEquals
import org.junit.Test

class MaskNumbersTest {
    @Test fun `long numbers keep only their last 4 digits, amounts stay`() {
        assertEquals(
            "INR 1,111.50 debited from A/C XXXXXXXX1234 Ref XXXXXXXX9012. Avl BAL INR 1,23,456.78",
            maskNumbers("INR 1,111.50 debited from A/C 999999991234 Ref 345678909012. Avl BAL INR 1,23,456.78"),
        )
        assertEquals("Rs.500 on 12-11-26", maskNumbers("Rs.500 on 12-11-26"))
    }
}
