package com.secondscreen.app.privileged

object ExternalDisplayTweaks {

    /** BLOCKING. Current override density, else physical density, else null. */
    fun getDensity(displayId: Int): Int? {
        return try {
            val r = ShizukuShell.exec("wm", "density", "-d", displayId.toString())
            if (!r.ok) return null
            val override = Regex("Override density:\\s*(\\d+)").find(r.message)
            val physical = Regex("Physical density:\\s*(\\d+)").find(r.message)
            (override ?: physical)?.groupValues?.get(1)?.toIntOrNull()
        } catch (t: Throwable) {
            null
        }
    }

    /** BLOCKING. */
    fun setDensity(displayId: Int, dpi: Int): PrivResult {
        return try {
            if (dpi <= 0) return PrivResult(false, "Densité invalide : $dpi")
            val r = ShizukuShell.exec("wm", "density", dpi.toString(), "-d", displayId.toString())
            if (r.ok) PrivResult(true, "Densité de l'écran $displayId réglée à $dpi dpi")
            else PrivResult(false, "Impossible de changer la densité : ${r.message}")
        } catch (t: Throwable) {
            PrivResult(false, "Impossible de changer la densité : ${t.describe()}")
        }
    }

    /** BLOCKING. */
    fun resetDensity(displayId: Int): PrivResult {
        return try {
            val r = ShizukuShell.exec("wm", "density", "reset", "-d", displayId.toString())
            if (r.ok) PrivResult(true, "Densité de l'écran $displayId réinitialisée")
            else PrivResult(false, "Impossible de réinitialiser la densité : ${r.message}")
        } catch (t: Throwable) {
            PrivResult(false, "Impossible de réinitialiser la densité : ${t.describe()}")
        }
    }
}
