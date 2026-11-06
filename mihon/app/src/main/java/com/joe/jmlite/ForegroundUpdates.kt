package com.joe.jmlite

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** One check per foreground opening, with the result attached to the current screen. */
object ForegroundUpdates : Application.ActivityLifecycleCallbacks {
    private var installed=false
    private var started=0
    private var current:Activity?=null
    private var update:AppUpdate?=null
    private val handler=Handler(Looper.getMainLooper())
    private val background=Runnable { if(started==0){update?.close();update=null;current=null} }
    @JvmStatic fun install(app:Application){if(!installed){installed=true;app.registerActivityLifecycleCallbacks(this)}}
    @JvmStatic fun checkAfterGuide(){val activity=current?:return;if(!activity.getSharedPreferences("settings",0).getBoolean("first_run_guide_acknowledged",false))return;val existing=update;if(existing!=null)existing.attach(activity) else {update=AppUpdate(activity).also{it.onResume()}}}
    override fun onActivityStarted(activity:Activity){handler.removeCallbacks(background);started++}
    override fun onActivityResumed(activity:Activity){if(activity !is eu.kanade.tachiyomi.ui.reader.ReaderActivity)EdgeNavigation.style(activity,(activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES);current=activity;checkAfterGuide()}
    override fun onActivityPaused(activity:Activity){if(current===activity){update?.onPause();current=null}}
    override fun onActivityStopped(activity:Activity){started--;if(started==0)handler.postDelayed(background,500)}
    override fun onActivityDestroyed(activity:Activity){}
    override fun onActivityCreated(activity:Activity,state:Bundle?){}
    override fun onActivitySaveInstanceState(activity:Activity,state:Bundle){}
}
