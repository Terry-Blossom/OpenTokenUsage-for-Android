package com.opentokenusage.android

import com.opentokenusage.android.domain.logic.ManualUsdRateInputController
import com.opentokenusage.android.domain.logic.ManualUsdRateUpdate
import com.opentokenusage.android.domain.logic.formatManualUsdRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualUsdRateInputTest {
    @Test
    fun keepsLocalTextStableWhileTypingDecimalsAndTrailingZeros() {
        val controller = ManualUsdRateInputController(initialRate = null)
        assertEquals("", controller.text)

        // Typing "150" commits 150.0, and echoing 150.0 does NOT rewrite text to "150.0"
        assertEquals(ManualUsdRateUpdate.Commit(150.0), controller.onUserInput("150"))
        assertNull(controller.onExternalRateChanged(150.0))
        assertEquals("150", controller.text)

        // Typing "150." keeps trailing dot without redundant commit
        assertEquals(ManualUsdRateUpdate.Ignore, controller.onUserInput("150."))
        assertEquals("150.", controller.text)

        // Typing "150.5" commits 150.5 and echoing keeps text intact
        assertEquals(ManualUsdRateUpdate.Commit(150.5), controller.onUserInput("150.5"))
        assertNull(controller.onExternalRateChanged(150.5))
        assertEquals("150.5", controller.text)

        // Typing "150.50" keeps trailing zero without rewriting text
        assertEquals(ManualUsdRateUpdate.Ignore, controller.onUserInput("150.50"))
        assertEquals("150.50", controller.text)
    }

    @Test
    fun ignoresIntermediateInvalidInputUntilValidValueEntered() {
        val controller = ManualUsdRateInputController(initialRate = 150.0)
        assertEquals("150", controller.text)

        assertEquals(ManualUsdRateUpdate.Ignore, controller.onUserInput("0"))
        assertEquals("0", controller.text)

        assertEquals(ManualUsdRateUpdate.Ignore, controller.onUserInput("0."))
        assertEquals("0.", controller.text)

        assertEquals(ManualUsdRateUpdate.Ignore, controller.onUserInput("."))
        assertEquals(".", controller.text)

        assertEquals(ManualUsdRateUpdate.Ignore, controller.onUserInput("-5"))
        assertEquals("-5", controller.text)

        assertEquals(ManualUsdRateUpdate.Commit(0.92), controller.onUserInput("0.92"))
        assertNull(controller.onExternalRateChanged(0.92))
        assertEquals("0.92", controller.text)

        // Clearing input commits null (auto rate)
        assertEquals(ManualUsdRateUpdate.Commit(null), controller.onUserInput(""))
        assertNull(controller.onExternalRateChanged(null))
        assertEquals("", controller.text)
    }

    @Test
    fun ignoresDelayedAsyncEchoesDuringFastTypingAndSyncsTrueExternalChanges() {
        val controller = ManualUsdRateInputController(initialRate = null)

        assertEquals(ManualUsdRateUpdate.Commit(1.0), controller.onUserInput("1"))
        assertEquals(ManualUsdRateUpdate.Commit(15.0), controller.onUserInput("15"))
        assertEquals(ManualUsdRateUpdate.Commit(154.0), controller.onUserInput("154"))

        // Async echoes arrive after user already finished typing "154"
        assertNull(controller.onExternalRateChanged(1.0))
        assertEquals("154", controller.text)
        assertNull(controller.onExternalRateChanged(15.0))
        assertEquals("154", controller.text)
        assertNull(controller.onExternalRateChanged(154.0))
        assertEquals("154", controller.text)

        // External settings import changes rate to 148.25 -> syncs local text
        assertEquals("148.25", controller.onExternalRateChanged(148.25))
        assertEquals("148.25", controller.text)

        // External settings import clears rate -> syncs to empty string
        assertEquals("", controller.onExternalRateChanged(null))
        assertEquals("", controller.text)
    }

    @Test
    fun formatsValidAndInvalidRatesCleanly() {
        assertEquals("", formatManualUsdRate(null))
        assertEquals("", formatManualUsdRate(0.0))
        assertEquals("", formatManualUsdRate(-10.0))
        assertEquals("150", formatManualUsdRate(150.0))
        assertEquals("152.5", formatManualUsdRate(152.5))
    }
}
