package com.joe.jmlite

import android.app.Activity
import android.content.SharedPreferences
import android.util.Base64
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PrivacyRecoveryTest {
    @AfterEach fun cleanup(){PrivacyVault.resetSession();unmockkAll()}
    private class Fixture {
        val activity=mockk<Activity>()
        val preferences=mockk<SharedPreferences>()
        val editor=mockk<SharedPreferences.Editor>(relaxed=true)
        var hash="existing-pin-hash"
        init {
            PrivacyVault.resetSession();mockkStatic(Base64::class)
            every{activity.getSharedPreferences("privacy_shelf",0)}returns preferences
            every{preferences.getString("hash","")}answers{hash}
            every{preferences.edit()}returns editor
            every{editor.putString(any(),any())}answers{if(firstArg<String>()=="hash")hash=secondArg();editor}
            every{Base64.encodeToString(any(),any())}returns "salt"
        }
    }
    @Test fun `only the confirmed exact recovery number disables the pin`(){
        val f=Fixture()
        for(code in listOf(null,"","11451","114515","0114514","114514 ","１１４５１４"))assertFalse(PrivacyVault.disableWithRecovery(f.activity,code))
        assertEquals("existing-pin-hash",f.hash)
        verify(exactly=0){f.preferences.edit()}
    }
    @Test fun `correct recovery clears the pin without changing the first use decision`(){
        val f=Fixture();assertTrue(PrivacyVault.disableWithRecovery(f.activity,"114514"));assertEquals("",f.hash)
        verify(exactly=1){f.editor.putString("hash","")}
        verify(exactly=1){f.editor.apply()}
        verify(exactly=0){f.editor.putBoolean(any(),any())}
    }
    @Test fun `correct recovery allows entry without prompting for the old pin`(){
        val f=Fixture();PrivacyVault.disableWithRecovery(f.activity,"114514")
        var entered=0;PrivacyVault.enter(f.activity){entered++}
        assertEquals(1,entered)
    }
    @Test fun `disabled pin remains disabled after session reset`(){
        val f=Fixture();PrivacyVault.disableWithRecovery(f.activity,"114514");PrivacyVault.resetSession()
        var entered=0;PrivacyVault.enter(f.activity){entered++}
        assertEquals(1,entered);assertEquals("",f.hash)
    }
    @Test fun `password state refreshes immediately when the stored pin changes`(){
        val f=Fixture();assertTrue(PrivacyVault.hasPassword(f.activity))
        PrivacyVault.disableWithRecovery(f.activity,"114514");assertFalse(PrivacyVault.hasPassword(f.activity))
        f.editor.putString("hash","new-pin-hash").apply()
        assertTrue(PrivacyVault.hasPassword(f.activity))
    }
    @Test fun `an unlocked shelf session does not mask an existing password for settings`(){
        val f=Fixture();f.hash="";PrivacyVault.enter(f.activity){}
        f.hash="newly-configured-pin-hash"
        assertTrue(PrivacyVault.hasPassword(f.activity))
    }
}
