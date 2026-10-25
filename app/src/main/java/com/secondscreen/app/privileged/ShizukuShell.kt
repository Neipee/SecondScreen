package com.secondscreen.app.privileged

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object ShizukuShell {
    const val REQUEST_CODE = 4242
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val TIMEOUT_SECONDS = 10L

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (t: Throwable) {
        false
    }

    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        isRunning() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun requestPermission() {
        try {
            if (isRunning() && !Shizuku.isPreV11()) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (ignored: Throwable) {
        }
    }

    fun addPermissionResultListener(listener: (Boolean) -> Unit): Any {
        val l = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode == REQUEST_CODE) {
                    listener(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
        }
        try {
            Shizuku.addRequestPermissionResultListener(l)
        } catch (ignored: Throwable) {
        }
        return l
    }

    fun removePermissionResultListener(token: Any) {
        try {
            if (token is Shizuku.OnRequestPermissionResultListener) {
                Shizuku.removeRequestPermissionResultListener(token)
            }
        } catch (ignored: Throwable) {
        }
    }

    /** Returns null if Shizuku is usable, otherwise an explanatory error. */
    internal fun checkReady(): PrivResult? {
        if (!isRunning()) {
            return PrivResult(
                false,
                "Shizuku non démarré : ouvrez l'application Shizuku et démarrez-la (débogage sans fil)"
            )
        }
        if (!hasPermission()) {
            return PrivResult(false, "Permission Shizuku refusée : autorisez SecondScreen dans Shizuku")
        }
        return null
    }

    /** BLOCKING: call off the main thread. */
    fun exec(vararg cmd: String): PrivResult {
        try {
            if (cmd.isEmpty()) return PrivResult(false, "Commande vide")
            checkReady()?.let { return it }
            val argv: Array<String> = if (cmd.size == 1) arrayOf("sh", "-c", cmd[0]) else arrayOf(*cmd)

            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }

            val process = try {
                method.invoke(null, argv, null, null) as Process
            } catch (e: InvocationTargetException) {
                throw e.targetException ?: e
            }

            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = readerThread(process.inputStream, out)
            val tErr = readerThread(process.errorStream, err)
            try {
                process.outputStream.close()
            } catch (ignored: Throwable) {
            }

            // Wait on a helper thread: the remote process' timed waitFor is not reliable over binder.
            val exitCode = AtomicInteger(Int.MIN_VALUE)
            val waiter = Thread {
                try {
                    exitCode.set(process.waitFor())
                } catch (ignored: Throwable) {
                }
            }
            waiter.isDaemon = true
            waiter.start()
            waiter.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            if (waiter.isAlive) {
                try {
                    process.destroy()
                } catch (ignored: Throwable) {
                }
                tOut.join(1000)
                tErr.join(1000)
                return PrivResult(false, "Délai dépassé (${TIMEOUT_SECONDS}s) pour : ${argv.joinToString(" ")}")
            }
            tOut.join(2000)
            tErr.join(2000)
            val code = exitCode.get()
            if (code == Int.MIN_VALUE) {
                return PrivResult(false, "Processus Shizuku interrompu : ${argv.joinToString(" ")}")
            }
            val text = buildString {
                synchronized(out) { append(out.toString().trim()) }
                val e = synchronized(err) { err.toString().trim() }
                if (e.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(e)
                }
            }
            return if (code == 0) {
                PrivResult(true, text)
            } else {
                PrivResult(false, if (text.isEmpty()) "Code de sortie $code" else "Code de sortie $code : $text")
            }
        } catch (t: Throwable) {
            return PrivResult(false, "Échec de l'exécution via Shizuku : ${t.describe()}")
        }
    }

    private fun readerThread(stream: InputStream, sink: StringBuilder): Thread {
        val t = Thread {
            try {
                stream.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(sink) { sink.append(buf, 0, n) }
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
        t.isDaemon = true
        t.start()
        return t
    }
}
