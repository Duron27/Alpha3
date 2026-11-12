package org.openmw.utils

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * Brings the launcher back after the game, in a process of its own (":restart"): the game's process cannot start the
 * engine a second time, so it is ended, and an activity started from inside it would end with it. This one (on screen,
 * so it may start activities) ends the game's process ([EXTRA_PID]), starts [EXTRA_TARGET] in a fresh one, and goes.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getIntExtra(EXTRA_PID, -1)
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid)
        intent.getStringExtra(EXTRA_TARGET)?.let { target ->
            startActivity(Intent().setClassName(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        finish()
        Process.killProcess(Process.myPid())
    }

    companion object {
        const val EXTRA_PID = "pid"
        const val EXTRA_TARGET = "target"
    }
}
