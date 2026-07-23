package com.phonecam.streamer

import android.app.Application

class PhoneCamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // SDK initialization that must happen before any Activity:
        // - AdMob init is deliberately NOT here — it's gated behind UMP
        //   consent in MainActivity, so it only runs after the user has
        //   consented (or been determined to not need consent).
        // - CameraX init is implicit (ProcessCameraProvider handles it).
    }
}
