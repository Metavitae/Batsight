package com.metavitae.soundlab

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * "Kithe presents The Batsight" opening (Chat's 3D design, assets/intro/). Plays only when the app is
 * opened from its icon (or `--es mode intro` for tests), never before a measurement, so its sound can
 * never reach the sonar. Tap skips. Calm mode (Android "Remove animations") = still logo. Phone on
 * silent or vibrate = no sound.
 */
object Intro {
    @SuppressLint("SetJavaScriptEnabled")
    fun show(act: Activity, statsId: String?, then: () -> Unit) {
        val calm = Settings.Global.getFloat(act.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        val am = act.getSystemService(AudioManager::class.java)
        val mute = am.ringerMode != AudioManager.RINGER_MODE_NORMAL
        val web = WebView(act)
        web.setBackgroundColor(Color.parseColor("#0B1526"))
        web.settings.javaScriptEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false   // the intro plays by itself
        var done = false
        web.addJavascriptInterface(object {
            @JavascriptInterface fun stats(json: String) {
                Log.i("BatsightIntro", json)
                if (statsId != null) Results.save(act, "$statsId.json", "application/json", json.toByteArray())
            }
            @JavascriptInterface fun done() {
                act.runOnUiThread {
                    if (done) return@runOnUiThread
                    done = true
                    web.destroy()
                    then()
                }
            }
        }, "Intro")
        act.window.decorView.setBackgroundColor(Color.parseColor("#0B1526"))
        act.setContentView(web)
        web.loadUrl("file:///android_asset/intro/intro.html?calm=${if (calm) 1 else 0}&mute=${if (mute) 1 else 0}")
    }
}
