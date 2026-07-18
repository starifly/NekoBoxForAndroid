package io.nekohasekai.sagernet.ktx

import libcore.Libcore
import java.io.InputStream
import java.io.OutputStream

object Logs {

    @Volatile
    internal var sink: (String) -> Unit = { Libcore.nekoLogPrintln(it) }

    private fun mkTag(): String {
        val stackTrace = Thread.currentThread().stackTrace
        return stackTrace[4].className.substringAfterLast(".")
    }

    // level int use logrus.go

    fun d(message: String) {
        sink("[Debug] [${mkTag()}] $message")
    }

    fun d(message: String, exception: Throwable) {
        sink("[Debug] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun i(message: String) {
        sink("[Info] [${mkTag()}] $message")
    }

    fun i(message: String, exception: Throwable) {
        sink("[Info] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun w(message: String) {
        sink("[Warning] [${mkTag()}] $message")
    }

    fun w(message: String, exception: Throwable) {
        sink("[Warning] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun w(exception: Throwable) {
        sink("[Warning] [${mkTag()}] " + exception.stackTraceToString())
    }

    fun e(message: String) {
        sink("[Error] [${mkTag()}] $message")
    }

    fun e(message: String, exception: Throwable) {
        sink("[Error] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun e(exception: Throwable) {
        sink("[Error] [${mkTag()}] " + exception.stackTraceToString())
    }
}

fun InputStream.use(out: OutputStream) {
    use { input ->
        out.use { output ->
            input.copyTo(output)
        }
    }
}
