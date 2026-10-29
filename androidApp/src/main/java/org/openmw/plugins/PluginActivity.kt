package org.openmw.plugins

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Full-screen host for a view a plugin builds (see [PluginHost.openScreen]). */
class PluginActivity : AppCompatActivity() {
    private var id = 0
    private val closer = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(EXTRA_ID, -1) == id) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getIntExtra(EXTRA_ID, 0)
        val (factory, screen) = Plugins.screen(id) ?: return finish()
        val filter = IntentFilter(CLOSE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(closer, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(closer, filter)
        try {
            setContentView(factory.create(this, screen))
        } catch (t: Throwable) {
            android.util.Log.e("Plugins", "Plugin screen failed", t)
            finish()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(closer) }
        Plugins.forget(id)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "plugin_screen"
        const val CLOSE_ACTION = "org.openmw.plugins.CLOSE_SCREEN"
    }
}
