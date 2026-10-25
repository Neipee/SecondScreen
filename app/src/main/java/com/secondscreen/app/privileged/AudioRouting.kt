package com.secondscreen.app.privileged

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

object AudioRouting {

    private val TARGET_USAGES = intArrayOf(
        AudioAttributes.USAGE_MEDIA,
        AudioAttributes.USAGE_GAME,
        AudioAttributes.USAGE_UNKNOWN
    )

    /** BLOCKING: call off the main thread. */
    fun forcePhoneSpeaker(ctx: Context, enable: Boolean): PrivResult {
        return try {
            ShizukuShell.checkReady()?.let { return it }
            val failures = mutableListOf<String>()

            val a = try {
                methodA(ctx, enable)
            } catch (t: Throwable) {
                PrivResult(false, "Méthode A (stratégies audio) : ${unwrap(t).describe()}")
            }
            if (a.ok && enable) {
                return PrivResult(true, "Son forcé sur le haut-parleur du téléphone (méthode utilisée : ${a.message})")
            }
            if (!a.ok) failures += a.message

            val b = methodB(enable)
            if (!enable) {
                val parts = mutableListOf<String>()
                parts += if (a.ok) "méthode A annulée (${a.message})" else a.message
                parts += b.message
                return PrivResult(a.ok || b.ok, "Routage audio par défaut restauré : " + parts.joinToString(" ; "))
            }
            if (b.ok) {
                return PrivResult(
                    true,
                    "Méthode utilisée : ${b.message}. Attention, cela n'aide que les adaptateurs HDMI audio USB. " +
                        "Échec précédent : ${failures.joinToString(" ; ")}"
                )
            }
            failures += b.message
            PrivResult(false, "Impossible de forcer le haut-parleur : " + failures.joinToString(" ; "))
        } catch (t: Throwable) {
            PrivResult(false, "Erreur lors du routage audio : ${unwrap(t).describe()}")
        }
    }

    private fun unwrap(t: Throwable): Throwable =
        if (t is InvocationTargetException && t.targetException != null) t.targetException else t

    private fun methodA(ctx: Context, enable: Boolean): PrivResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return PrivResult(false, "Méthode A indisponible : Android 11 minimum requis")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
            } catch (ignored: Throwable) {
            }
        }

        val binder: IBinder = SystemServiceHelper.getSystemService("audio")
            ?: return PrivResult(false, "Méthode A : service audio introuvable")
        val wrapper = ShizukuBinderWrapper(binder)
        val service = Class.forName("android.media.IAudioService\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, wrapper)
            ?: return PrivResult(false, "Méthode A : IAudioService indisponible")

        val strategyIds = findStrategyIds()
        if (strategyIds.isEmpty()) {
            return PrivResult(false, "Méthode A : aucune stratégie audio média trouvée")
        }

        val methods = service.javaClass.methods
        val errors = mutableListOf<String>()
        var successCount = 0

        if (enable) {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val speaker = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?: return PrivResult(false, "Méthode A : haut-parleur interne introuvable")
            val adaClass = Class.forName("android.media.AudioDeviceAttributes")
            val ada = adaClass.getConstructor(AudioDeviceInfo::class.java).newInstance(speaker)

            val multi = findMethod(methods, "setPreferredDevicesForStrategy", 2) { it[1] == List::class.java }
            val single = findMethod(methods, "setPreferredDeviceForStrategy", 2) { it[1] == adaClass }
            if (multi == null && single == null) {
                return PrivResult(false, "Méthode A : setPreferredDevice(s)ForStrategy absent sur cet appareil")
            }
            for (id in strategyIds) {
                val err = try {
                    val res = if (multi != null) multi.invoke(service, id, listOf(ada))
                    else single!!.invoke(service, id, ada)
                    if (res is Int && res != 0) "stratégie $id : code $res" else null
                } catch (t: Throwable) {
                    "stratégie $id : ${unwrap(t).describe()}"
                }
                if (err == null) successCount++ else errors += err
            }
            val name = if (multi != null) "setPreferredDevicesForStrategy" else "setPreferredDeviceForStrategy"
            return if (successCount > 0) {
                PrivResult(true, "$name sur $successCount stratégie(s) audio" +
                    if (errors.isEmpty()) "" else " (erreurs : ${errors.joinToString(", ")})")
            } else {
                PrivResult(false, "Méthode A ($name) : ${errors.joinToString(", ")}")
            }
        } else {
            val remove = findMethod(methods, "removePreferredDevicesForStrategy", 1) { true }
                ?: findMethod(methods, "removePreferredDeviceForStrategy", 1) { true }
                ?: return PrivResult(false, "Méthode A : removePreferredDevice(s)ForStrategy absent")
            for (id in strategyIds) {
                val err = try {
                    val res = remove.invoke(service, id)
                    if (res is Int && res != 0) "stratégie $id : code $res" else null
                } catch (t: Throwable) {
                    "stratégie $id : ${unwrap(t).describe()}"
                }
                if (err == null) successCount++ else errors += err
            }
            return if (successCount > 0) {
                PrivResult(true, "${remove.name} sur $successCount stratégie(s)")
            } else {
                PrivResult(false, "Méthode A (${remove.name}) : ${errors.joinToString(", ")}")
            }
        }
    }

    private fun findMethod(
        methods: Array<Method>,
        name: String,
        paramCount: Int,
        extra: (Array<Class<*>>) -> Boolean
    ): Method? = methods.firstOrNull { m ->
        m.name == name && m.parameterTypes.size == paramCount &&
            (m.parameterTypes[0] == Int::class.javaPrimitiveType || m.parameterTypes[0] == Int::class.javaObjectType) &&
            extra(m.parameterTypes)
    }

    private fun findStrategyIds(): List<Int> {
        val cls = Class.forName("android.media.audiopolicy.AudioProductStrategy")
        val strategies = cls.getMethod("getAudioProductStrategies").invoke(null) as? List<*>
            ?: return emptyList()
        val supports = cls.getMethod("supportsAudioAttributes", AudioAttributes::class.java)
        val getId = cls.getMethod("getId")
        val attrs = TARGET_USAGES.map { AudioAttributes.Builder().setUsage(it).build() }
        val ids = LinkedHashSet<Int>()
        for (s in strategies) {
            if (s == null) continue
            try {
                val match = attrs.any { a -> supports.invoke(s, a) == true }
                if (match) {
                    val id = getId.invoke(s)
                    if (id is Int) ids += id
                }
            } catch (ignored: Throwable) {
            }
        }
        return ids.toList()
    }

    private fun methodB(enable: Boolean): PrivResult {
        val value = if (enable) "1" else "0"
        val r = ShizukuShell.exec("settings", "put", "secure", "usb_audio_automatic_routing_disabled", value)
        return if (r.ok) {
            PrivResult(true, "Méthode B (usb_audio_automatic_routing_disabled=$value)")
        } else {
            PrivResult(false, "Méthode B (usb_audio_automatic_routing_disabled) : ${r.message}")
        }
    }

    /** Main thread. Opens the system media output picker, or sound settings as a fallback. */
    fun openSystemOutputSwitcher(ctx: Context): PrivResult {
        val errors = mutableListOf<String>()
        try {
            val i = Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
                .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", ctx.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            return PrivResult(true, "Sélecteur de sortie audio ouvert")
        } catch (t: Throwable) {
            errors += "panneau sortie média : ${t.describe()}"
        }
        try {
            ctx.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return PrivResult(true, "Paramètres Son ouverts (sélecteur de sortie indisponible)")
        } catch (t: Throwable) {
            errors += "paramètres son : ${t.describe()}"
        }
        return PrivResult(false, "Impossible d'ouvrir le sélecteur de sortie audio : " + errors.joinToString(" ; "))
    }
}
