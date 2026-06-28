package moe.matsuri.nb4a.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.use
import io.nekohasekai.sagernet.utils.CrashHandler
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object SendLog {
    // Cap the neko.log slice included in an exported report (keeps memory bounded).
    private const val MAX_EXPORT_LOG_BYTES = 4L * 1024 * 1024

    // Create full log and send
    fun sendLog(context: Context, title: String) {
        val logFile = File.createTempFile(
            "$title ",
            ".log",
            File(app.cacheDir, "log").also { it.mkdirs() },
        )

        var report = CrashHandler.buildReportHeader()

        report += "Logcat: \n\n"

        logFile.writeText(report)

        try {
            // Logcat output is plain (no ANSI), so stream it straight to the file to
            // keep memory O(1) instead of buffering + stripping it.
            val process = ProcessBuilder("logcat", "-d").start()
            process.inputStream.use(FileOutputStream(logFile, true))
            if (process.waitFor() != 0) {
                logFile.appendText("Export logcat error: logcat exited ${process.exitValue()}")
            }
            logFile.appendText("\n")
        } catch (e: Exception) {
            Logs.w(e)
            logFile.appendText("Export logcat error: " + CrashHandler.formatThrowable(e))
        }

        logFile.appendText("\n")
        // Strip ANSI color codes so a shared .log opened in a text editor is readable
        // (the in-app Logs screen renders the colors instead). neko.log is size-capped
        // by maxLogSizeKb, but bound the export defensively to avoid large copies.
        logFile.appendText(AnsiLog.strip(String(getNekoLog(MAX_EXPORT_LOG_BYTES))))

        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/x-log")
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(
                        Intent.EXTRA_STREAM,
                        FileProvider.getUriForFile(
                            context,
                            BuildConfig.APPLICATION_ID + ".cache",
                            logFile,
                        ),
                    ),
                context.getString(R.string.abc_shareactionprovider_share_with),
            ),
        )
    }

    // Get log bytes from neko.log
    fun getNekoLog(max: Long): ByteArray {
        return try {
            val file = File(
                SagerNet.application.cacheDir,
                "neko.log",
            )
            val len = file.length()
            val stream = FileInputStream(file)
            if (max in 1 until len) {
                stream.skip(len - max) // TODO string?
            }
            stream.use { it.readBytes() }
        } catch (e: Exception) {
            e.stackTraceToString().toByteArray()
        }
    }
}
