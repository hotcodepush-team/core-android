package com.hotcodepush.protocol

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/** The SDK's debug screen, one implementation every framework's SDK presents: `showDebugScreen()` ends here. */
object DebugScreen {
    /** The core the screen reads, held weakly: the SDK that showed the screen owns it. */
    @Volatile
    internal var core: WeakReference<Core>? = null

    fun show(context: Context, core: Core) {
        this.core = WeakReference(core)
        val intent = Intent(context, DebugScreenActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/** What `DebugReport` renders as one selectable text under two buttons: a check now, and the same text into the system share sheet. */
class DebugScreenActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var report: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = DebugScreen.core?.get()
        if (core == null) {
            finish()
            return
        }
        val padding = (PADDING_DP * resources.displayMetrics.density).toInt()
        val report = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = TEXT_SIZE_SP
            setTextIsSelectable(true)
            setPadding(padding, padding, padding, padding)
        }
        val checkButton = Button(this).apply { setText(R.string.hotcodepush_debug_screen_check_now) }
        checkButton.setOnClickListener { checkNow(core, checkButton) }
        val shareButton = Button(this).apply { setText(R.string.hotcodepush_debug_screen_share) }
        shareButton.setOnClickListener { share() }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(padding, padding, padding, 0)
            addView(checkButton)
            addView(shareButton)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(buttons)
            addView(ScrollView(context).apply { addView(report) })
        }
        this.report = report
        setContentView(content)
        render(core)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    /** The first stage alone: the check answers why, and downloads and reloads nothing behind the screen. */
    private fun checkNow(core: Core, button: Button) {
        button.isEnabled = false
        scope.launch {
            core.checkForUpdate()
            button.isEnabled = true
            render(core)
        }
    }

    private fun render(core: Core) {
        report?.text = DebugReport.text(core.debugSnapshot())
    }

    private fun share() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report?.text?.toString() ?: "")
        startActivity(Intent.createChooser(intent, null))
    }

    private companion object {
        const val PADDING_DP = 16
        const val TEXT_SIZE_SP = 12f
    }
}
