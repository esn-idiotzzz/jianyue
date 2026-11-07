package com.joe.jmlite

import android.graphics.drawable.Drawable
import android.widget.ImageView
import io.mockk.*
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class CoverBindingTest {
    @AfterEach fun cleanup() = unmockkAll()

    private fun book(key:String)=mockk<JSONObject>(relaxed=true).also {
        every{it.optString("key",any())}returns key
    }
    private fun loader()=spyk(mockk<CoverLoader>(relaxed=true)).also {
        CoverLoader::class.java.getDeclaredField("closed").apply{isAccessible=true}.set(it,true)
    }

    @Test fun `rebinding the same visible cover keeps its drawable`() {
        val image=mockk<ImageView>(relaxed=true)
        every{image.getTag(any())}returns "book-one"
        every{image.drawable}returns mockk<Drawable>()
        loader().load(image,book("book-one"))
        verify(exactly=0){image.setImageDrawable(any())}
        verify(exactly=0){image.setTag(any(),any())}
    }

    @Test fun `recycling a cover to a different book clears the previous image`() {
        val image=mockk<ImageView>(relaxed=true)
        every{image.getTag(any())}returns "book-one"
        every{image.drawable}returns mockk<Drawable>()
        loader().load(image,book("book-two"))
        verify{image.setTag(any(),"book-two");image.setImageDrawable(null)}
    }

    @Test fun `a missing cover is still eligible for another load`() {
        val image=mockk<ImageView>(relaxed=true)
        every{image.getTag(any())}returns "book-one"
        every{image.drawable}returns null
        loader().load(image,book("book-one"))
        verify{image.setTag(any(),"book-one")}
    }
}
