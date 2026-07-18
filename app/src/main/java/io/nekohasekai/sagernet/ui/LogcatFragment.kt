package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutLogcatBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.ListListener
import libcore.Libcore
import moe.matsuri.nb4a.utils.AnsiLog
import moe.matsuri.nb4a.utils.SendLog

class LogcatFragment :
    ToolbarFragment(R.layout.layout_logcat),
    Toolbar.OnMenuItemClickListener {

    lateinit var binding: LayoutLogcatBinding

    @SuppressLint("RestrictedApi", "WrongConstant")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_log)

        toolbar.inflateMenu(R.menu.logcat_menu)
        toolbar.setOnMenuItemClickListener(this)

        binding = LayoutLogcatBinding.bind(view)

        binding.textview.breakStrategy = 0 // simple

        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)

        reloadSession()
    }

    private fun getColorForLine(line: String): ForegroundColorSpan {
        var color = ForegroundColorSpan(requireContext().getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
        when {
            line.contains("INFO[") || line.contains(" [Info]") -> {
                color = ForegroundColorSpan(requireContext().getColour(R.color.ui_success))
            }

            line.contains("ERROR[") || line.contains(" [Error]") -> {
                color = ForegroundColorSpan(requireContext().getColour(R.color.ui_error))
            }

            line.contains("WARN[") || line.contains(" [Warning]") -> {
                color = ForegroundColorSpan(requireContext().getColour(R.color.ui_warning))
            }
        }
        return color
    }

    private fun reloadSession() {
        // sing-box bakes ANSI color codes into each log line, so parse them into
        // foreground spans (instead of rendering the raw escape codes as text) and
        // keep the colors the core emits (cyan INFO, yellow WARN, red ERROR, the
        // per-connection-id color, ...).
        val rendered = AnsiLog.render(String(SendLog.getNekoLog(50 * 1024)))
        val span = SpannableString(rendered.text)
        // Dim default for lines the core emits without an ANSI color (e.g. plain
        // go-log lines), matching the previous viewer's gray fallback.
        if (rendered.text.isNotEmpty()) {
            span.setSpan(
                ForegroundColorSpan(Color.GRAY),
                0,
                rendered.text.length,
                SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        for (run in rendered.spans) {
            span.setSpan(
                ForegroundColorSpan(run.color),
                run.start,
                run.end,
                SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        binding.textview.text = span
        binding.textview.clearFocus()
        // wait for the textview to finish its final layout before scrolling to the bottom
        binding.textview.doOnLayout {
            binding.scroolview.scrollTo(0, binding.textview.height)
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_clear_logcat -> {
                runOnDefaultDispatcher {
                    try {
                        Libcore.nekoLogClear()
                        Runtime.getRuntime().exec("/system/bin/logcat -c")
                    } catch (e: Exception) {
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                        return@runOnDefaultDispatcher
                    }
                    onMainDispatcher {
                        binding.textview.text = ""
                    }
                }
            }

            R.id.action_send_logcat -> {
                val context = requireContext()
                runOnDefaultDispatcher {
                    SendLog.sendLog(context, "NB4A")
                }
            }

            R.id.action_refresh -> {
                reloadSession()
            }
        }
        return true
    }

}
