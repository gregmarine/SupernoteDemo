package org.iccnet.supernotedemo

import android.app.Application
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

class SupernoteDemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android 11+ blocks reflection onto non-SDK ("hidden") classes such as
        // android.os.ServiceManager and the firmware "eink" system service. An empty
        // signature prefix exempts everything, which is what SupernoteInk needs.
        try {
            HiddenApiBypass.addHiddenApiExemptions("")
        } catch (t: Throwable) {
            Log.w("SupernoteDemoApp", "HiddenApiBypass failed; firmware ink may be unavailable", t)
        }
    }
}
