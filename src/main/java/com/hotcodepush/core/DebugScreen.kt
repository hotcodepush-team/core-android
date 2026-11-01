package com.hotcodepush.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/** The debug screen every SDK presents through `showDebugScreen()`. */
object DebugScreen {
    // Held weakly, since the SDK that showed the screen owns the core.
    @Volatile
    internal var core: WeakReference<Core>? = null

    fun show(context: Context, core: Core) {
        this.core = WeakReference(core)
        val intent = Intent(context, DebugScreenActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/**
 * Shows the `DebugReport` text under its buttons: check now, return to the configured channel while a runtime choice is in
 * effect, and share the same text through the system share sheet.
 */
class DebugScreenActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var report: TextView? = null
    private var returnButton: Button? = null

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
        val returnButton = Button(this).apply { setText(R.string.hotcodepush_debug_screen_return_to_configured_channel) }
        returnButton.setOnClickListener { returnToConfiguredChannel(core) }
        val shareButton = Button(this).apply { setText(R.string.hotcodepush_debug_screen_share) }
        shareButton.setOnClickListener { share() }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(padding, padding, padding, 0)
            addView(checkButton)
            addView(returnButton)
            addView(shareButton)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(buttons)
            addView(ScrollView(context).apply { addView(report) })
        }
        this.report = report
        this.returnButton = returnButton
        setContentView(content)
        render(core)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // Only the check runs, so nothing downloads or reloads behind the screen. Android refuses the network on the main thread.
    private fun checkNow(core: Core, button: Button) {
        button.isEnabled = false
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.checkForUpdate() }
            } catch (mistake: PlainException) {
                // Nothing was checked: the report's channel section shows the id the check refused.
            }
            button.isEnabled = true
            render(core)
        }
    }

    private fun render(core: Core) {
        report?.text = DebugReport.text(core.debugSnapshot())
        returnButton?.visibility = if (core.getChannel().source == ChannelSource.RUNTIME) View.VISIBLE else View.GONE
    }

    private fun returnToConfiguredChannel(core: Core) {
        scope.launch {
            withContext(Dispatchers.IO) { core.setChannel(null) }
            render(core)
        }
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
