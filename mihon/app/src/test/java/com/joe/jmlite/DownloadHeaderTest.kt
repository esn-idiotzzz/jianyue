package com.joe.jmlite

import android.view.View
import android.widget.EditText
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DownloadHeaderTest {
    @AfterEach fun cleanup() = unmockkAll()

    // Android's superclass focus propagation is covered by DownloadInputFocusTest on a device.
    private fun remember(header:DownloadHeader,editor:EditText) {
        DownloadHeader::class.java.getDeclaredField("editor").apply{isAccessible=true}.set(header,editor)
    }

    @Test fun `resize focus returns to the most recently touched editor`() {
        val header=spyk(mockk<DownloadHeader>(relaxed=true))
        val jm=mockk<EditText>(relaxed=true)
        val online=mockk<EditText>(relaxed=true)
        every{jm.isShown}returns true
        every{online.isShown}returns true
        every{jm.requestFocus(any(),any())}returns true
        every{online.requestFocus(any(),any())}returns true
        for(editor in listOf(online,jm,online,jm)) {
            remember(header,editor)
            assertTrue(header.requestFocus(View.FOCUS_DOWN,null))
        }
        verifyOrder {
            online.requestFocus(View.FOCUS_DOWN,null)
            jm.requestFocus(View.FOCUS_DOWN,null)
            online.requestFocus(View.FOCUS_DOWN,null)
            jm.requestFocus(View.FOCUS_DOWN,null)
        }
    }

    @Test fun `a header focus request does not target an editor that is no longer visible`() {
        val header=spyk(mockk<DownloadHeader>(relaxed=true))
        val editor=mockk<EditText>(relaxed=true)
        every{editor.isShown}returns false
        remember(header,editor)
        // Delegation reaches the Android stub in this plain JVM runtime.
        val error=assertThrows(RuntimeException::class.java){header.requestFocus(View.FOCUS_DOWN,null)}
        assertTrue(error.message.orEmpty().contains("not mocked"))
        verify(exactly=0){editor.requestFocus(any(),any())}
    }

    @Test fun `a rejected editor focus request is returned without selecting another input`() {
        val header=spyk(mockk<DownloadHeader>(relaxed=true))
        val editor=mockk<EditText>(relaxed=true)
        every{editor.isShown}returns true
        every{editor.requestFocus(any(),any())}returns false
        remember(header,editor)
        assertFalse(header.requestFocus(View.FOCUS_DOWN,null))
        verify(exactly=1){editor.requestFocus(View.FOCUS_DOWN,null)}
    }
}
