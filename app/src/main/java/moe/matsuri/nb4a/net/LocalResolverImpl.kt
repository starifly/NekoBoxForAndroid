package moe.matsuri.nb4a.net

import android.net.DnsResolver
import android.os.CancellationSignal
import android.system.ErrnoException
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import libcore.ExchangeContext
import libcore.LocalDNSTransport
import java.net.InetAddress

object LocalResolverImpl : LocalDNSTransport {

    // new local

    override fun raw(): Boolean = true

    override fun networkHandle(): Long = SagerNet.underlyingNetwork?.networkHandle ?: 0

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val signal = CancellationSignal()
        ctx.onCancel(signal::cancel)

        val callback = object : DnsResolver.Callback<ByteArray> {
            override fun onAnswer(answer: ByteArray, rcode: Int) {
                ctx.rawSuccess(answer)
            }

            override fun onError(error: DnsResolver.DnsException) {
                val cause = error.cause
                if (cause is ErrnoException) {
                    ctx.errnoCode(cause.errno)
                } else {
                    Logs.w(error)
                    ctx.errnoCode(114514)
                }
            }
        }

        DnsResolver.getInstance().rawQuery(
            SagerNet.underlyingNetwork,
            message,
            DnsResolver.FLAG_NO_RETRY,
            Dispatchers.IO.asExecutor(),
            signal,
            callback
        )
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val signal = CancellationSignal()
        ctx.onCancel(signal::cancel)

        val callback = object : DnsResolver.Callback<Collection<InetAddress>> {
            override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                try {
                    if (rcode == 0) {
                        ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                    } else {
                        ctx.errorCode(rcode)
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    ctx.errnoCode(114514)
                }
            }

            override fun onError(error: DnsResolver.DnsException) {
                try {
                    val cause = error.cause
                    if (cause is ErrnoException) {
                        ctx.errnoCode(cause.errno)
                    } else {
                        Logs.w(error)
                        ctx.errnoCode(114514)
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    ctx.errnoCode(114514)
                }
            }
        }

        val type = when {
            network.endsWith("4") -> DnsResolver.TYPE_A
            network.endsWith("6") -> DnsResolver.TYPE_AAAA
            else -> null
        }
        if (type != null) {
            DnsResolver.getInstance().query(
                SagerNet.underlyingNetwork,
                domain,
                type,
                DnsResolver.FLAG_NO_RETRY,
                Dispatchers.IO.asExecutor(),
                signal,
                callback
            )
        } else {
            DnsResolver.getInstance().query(
                SagerNet.underlyingNetwork,
                domain,
                DnsResolver.FLAG_NO_RETRY,
                Dispatchers.IO.asExecutor(),
                signal,
                callback
            )
        }
    }

}
