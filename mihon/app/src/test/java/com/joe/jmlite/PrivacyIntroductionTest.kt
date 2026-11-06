package com.joe.jmlite

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.SharedPreferences
import android.util.Base64
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PrivacyIntroductionTest {
    @AfterEach fun cleanup(){PrivacyVault.resetSession();unmockkAll()}
    private class Fixture {
        val activity=mockk<Activity>(relaxed=true)
        val preferences=mockk<SharedPreferences>()
        val editor=mockk<SharedPreferences.Editor>(relaxed=true)
        val builder=mockk<AlertDialog.Builder>()
        val dialog=mockk<AlertDialog>(relaxed=true)
        val negative=slot<DialogInterface.OnClickListener>()
        var introduced=false
        var actions=0
        init {
            PrivacyVault.resetSession()
            mockkStatic(PredictiveBack::class)
            mockkStatic(Base64::class)
            every { activity.getSharedPreferences("privacy_shelf",0) } returns preferences
            every { preferences.getBoolean("introduced",false) } answers { introduced }
            every { preferences.getString(any(),any()) } returns ""
            every { preferences.edit() } returns editor
            every { editor.putString(any(),any()) } returns editor
            every { editor.putBoolean("introduced",true) } answers { introduced=true;editor }
            every { Base64.encodeToString(any(),any()) } returns "salt"
            every { PredictiveBack.dialog(activity) } returns builder
            every { builder.setTitle(any<String>()) } returns builder
            every { builder.setMessage(any<String>()) } returns builder
            every { builder.setNegativeButton("暂不设置",capture(negative)) } returns builder
            every { builder.setPositiveButton("设置密码",any()) } returns builder
            every { builder.show() } returns dialog
        }
        fun pull()=PrivacyVault.enterFromShelf(activity){actions++}
        fun move()=PrivacyVault.beforeFirstUse(activity){actions++}
        fun decline()=negative.captured.onClick(dialog,DialogInterface.BUTTON_NEGATIVE)
    }
    @Test fun `pull entry waits for the first decision before opening`(){
        val f=Fixture();f.pull()
        assertEquals(0,f.actions);assertFalse(f.introduced)
        f.decline();assertTrue(f.introduced);assertEquals(1,f.actions)
    }
    @Test fun `choosing at the pull entry suppresses the subsequent move introduction`(){
        val f=Fixture();f.pull();f.decline();f.move()
        assertEquals(2,f.actions)
        verify(exactly=1){f.builder.show()}
    }
    @Test fun `choosing at the move entry suppresses the subsequent pull introduction`(){
        val f=Fixture();f.move();f.decline();f.pull()
        assertEquals(2,f.actions)
        verify(exactly=1){f.builder.show()}
    }
    @Test fun `canceling the first prompt neither opens nor marks the shared decision`(){
        val f=Fixture();f.pull();f.dialog.cancel()
        assertFalse(f.introduced);assertEquals(0,f.actions)
        f.move();verify(exactly=2){f.builder.show()}
        verify(exactly=0){f.editor.putBoolean("introduced",true)}
    }
}
