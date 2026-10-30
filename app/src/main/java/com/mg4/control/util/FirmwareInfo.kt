package com.mg4.control.util

import android.annotation.SuppressLint
import android.content.Context

/**
 * Détecte la génération de firmware à partir de ro.build.mt2712.version.
 *
 * SWI133 : "SWI133-xxxxx" — ADAS via getMixProperty(0x32), 5 modes, 2 alertes, sièges+volant chauffants
 * SWI132 : "SWI132-xxxxx" — ADAS via CarVehicleSettingClient (acc/tja getAccTjaState), alertes via binder
 *                            direct IVehicleSettingService (TX 0x128/0x12a), même section UI que SWI68
 * SWI68  : "SWI68-xxxxx"  — ADAS via VehicleSettingManager (acc/tja), sièges+volant chauffants
 * SWI69  : "SWI69-xxxxx"  — ADAS via VehicleSettingManager "nouvelle gen", sans sièges/volant chauffants
 * SWI131 : "SWI131-xxxxx" — Identique SWI69 (même package, même API), sans sièges/volant chauffants
 * SWI165 : "SWI165-xxxxx" — ADAS via VehicleSettingManager (même SDK que SWI68), AEB via setFcwAlarmMode
 *                            sièges+volant chauffants disponibles
 * UNKNOWN : firmware non reconnu — l'utilisateur peut forcer un mode de compatibilité
 */
object FirmwareInfo {

    enum class Gen { SWI133, SWI132, SWI68, SWI69, SWI131, SWI165, UNKNOWN }

    private const val PREF_NAME       = "mg4_settings"
    private const val PREF_FORCED_GEN = "forced_firmware_gen"

    @Volatile private var cached:         Gen?    = null
    @Volatile private var detectedString: String? = null
    @Volatile private var sContext:       Context? = null

    /**
     * À appeler au plus tôt dans MG4App.onCreate(), MG4ControlService.onCreate()
     * et MainActivity.onCreate(). Lit le choix forcé éventuel depuis les
     * SharedPreferences et l'applique au cache.
     */
    fun initWithContext(context: Context) {
        val app = context.applicationContext
        sContext = app
        val prefs = runCatching {
            val storageCtx = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N &&
                context.isDeviceProtectedStorage) context else app
            storageCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }.getOrNull() ?: return
        val forced = prefs.getString(PREF_FORCED_GEN, null) ?: return
        val forcedGen = runCatching { Gen.valueOf(forced) }.getOrDefault(Gen.UNKNOWN)
        if (forcedGen != Gen.UNKNOWN) {
            cached = forcedGen
        }
    }

    /**
     * Analyse la chaîne de version du firmware et détermine la génération correspondante.
     * Prend en compte les préfixes standard (SWI), distributeurs (ex: DBI pour la Turquie / Doğan Trend),
     * ainsi que les variantes régionales à 3 lettres.
     */
    fun parseGeneration(rawVersion: String?): Gen {
        val v = rawVersion?.trim()?.uppercase() ?: return Gen.UNKNOWN
        return when {
            v.startsWith("SWI165") || v.startsWith("DBI165") -> Gen.SWI165
            v.startsWith("SWI133") || v.startsWith("DBI133") -> Gen.SWI133
            v.startsWith("SWI132") || v.startsWith("DBI132") -> Gen.SWI132   // avant SWI131 — startsWith("SWI13") serait ambigu
            v.startsWith("SWI131") || v.startsWith("DBI131") -> Gen.SWI131
            v.startsWith("SWI68")  || v.startsWith("DBI68")  -> Gen.SWI68
            v.startsWith("SWI69")  || v.startsWith("DBI69")  -> Gen.SWI69
            Regex("""^(?:[A-Z]{2,4})?165\b""").containsMatchIn(v) -> Gen.SWI165
            Regex("""^(?:[A-Z]{2,4})?133\b""").containsMatchIn(v) -> Gen.SWI133
            Regex("""^(?:[A-Z]{2,4})?132\b""").containsMatchIn(v) -> Gen.SWI132
            Regex("""^(?:[A-Z]{2,4})?131\b""").containsMatchIn(v) -> Gen.SWI131
            Regex("""^(?:[A-Z]{2,4})?68\b""").containsMatchIn(v)  -> Gen.SWI68
            Regex("""^(?:[A-Z]{2,4})?69\b""").containsMatchIn(v)  -> Gen.SWI69
            else -> Gen.UNKNOWN
        }
    }

    /** Retourne la génération active (forcée ou détectée automatiquement). */
    fun getGeneration(context: Context? = null): Gen {
        cached?.let { if (it != Gen.UNKNOWN) return it }
        val ctx = context?.applicationContext ?: sContext
        if (ctx != null) {
            val forced = runCatching {
                val storageCtx = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N &&
                    ctx.isDeviceProtectedStorage) ctx else ctx.applicationContext
                storageCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .getString(PREF_FORCED_GEN, null)
            }.getOrNull()
            if (forced != null) {
                val forcedGen = runCatching { Gen.valueOf(forced) }.getOrDefault(Gen.UNKNOWN)
                if (forcedGen != Gen.UNKNOWN) {
                    cached = forcedGen
                    return forcedGen
                }
            }
        }
        val candidates = listOfNotNull(
            readProp("ro.build.display.id"),
            runCatching { android.os.Build.DISPLAY }.getOrNull()?.takeIf { it.isNotBlank() },
            readProp("ro.build.mt2712.version"),
            readProp("ro.build.version.incremental"),
            runCatching { android.os.Build.VERSION.INCREMENTAL }.getOrNull()?.takeIf { it.isNotBlank() },
            readProp("ro.system.build.version.incremental"),
            readProp("ro.vendor.build.version.incremental"),
            readProp("ro.product.build.version.incremental"),
            readProp("ro.build.id"),
            runCatching { android.os.Build.ID }.getOrNull()?.takeIf { it.isNotBlank() },
            readProp("ro.build.description")
        )
        for (version in candidates) {
            val gen = parseGeneration(version)
            if (gen != Gen.UNKNOWN) {
                detectedString = version
                cached = gen
                return gen
            }
        }
        if (detectedString == null) {
            detectedString = candidates.firstOrNull() ?: "?"
        }
        return Gen.UNKNOWN
    }

    /**
     * Chaîne brute lue depuis les propriétés système (ex: "SWI131-12345-xxx").
     * Utile pour afficher la version exacte à l'utilisateur dans le dialog de warning.
     */
    fun getDetectedString(): String {
        if (detectedString == null) getGeneration()
        return detectedString ?: "?"
    }

    /**
     * Force le mode de compatibilité manuellement.
     * Le choix est persisté dans les SharedPreferences et survit aux redémarrages.
     */
    fun forceGeneration(context: Context, gen: Gen) {
        cached = gen
        val app = context.applicationContext
        sContext = app
        app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_FORCED_GEN, gen.name).apply()
    }

    /** Utile pour les tests unitaires pour réinitialiser le cache. */
    fun resetCacheForTests() {
        cached = null
        detectedString = null
        sContext = null
    }

    /**
     * Retourne true si le mode de compatibilité a été forcé manuellement
     * (par opposition à une détection automatique réussie).
     */
    fun isForced(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .contains(PREF_FORCED_GEN)

    // ── Helpers de capacités ─────────────────────────────────────────────────

    /**
     * SWI69 et SWI131 utilisent la même API "nouvelle génération" VSM :
     *   package com.saicmotor.vehiclesetting.service
     *   constructeur IBinder-direct
     *   méthodes : setAccTjaState, setFcwState, setLasWarningSound…
     */
    fun isNewGenVsm(): Boolean {
        val gen = getGeneration()
        return gen == Gen.SWI69 || gen == Gen.SWI131
    }

    /** Firmwares « A9 » : SWI69, SWI131 et SWI132. */
    fun isA9(): Boolean {
        val gen = getGeneration()
        return gen == Gen.SWI69 || gen == Gen.SWI131 || gen == Gen.SWI132
    }

    /**
     * SWI68, SWI69, SWI131 et SWI165 utilisent tous le VehicleSettingManager SAIC
     * pour l'ADAS (ACC/TJA) et les alertes sonores.
     * SWI133 utilise VehiclePropertyManager (getMixProperty).
     */
    fun isVsmBased(): Boolean {
        val gen = getGeneration()
        return gen == Gen.SWI68 || gen == Gen.SWI69 || gen == Gen.SWI131 || gen == Gen.SWI132 || gen == Gen.SWI165
    }

    /**
     * SWI133, SWI68 et SWI165 ont les sièges chauffants et le volant chauffant.
     * SWI69 et SWI131 sont des finitions Standard/SE sans ces équipements.
     */
    fun hasHeatFeatures(context: Context? = null): Boolean {
        val gen = getGeneration(context)
        return gen == Gen.SWI133 || gen == Gen.SWI68 || gen == Gen.SWI165
    }

    /**
     * SWI165 utilise le même SDK VehicleSettingManager que SWI68.
     * L'AEB est contrôlé par setFcwAlarmMode() (même API que SWI68).
     * setAutoEmergencyBraking() existe dans le SDK mais n'est pas utilisé par l'app officielle.
     */
    fun isSWI165(): Boolean = getGeneration() == Gen.SWI165

    private fun readProp(key: String): String? = try {
        @SuppressLint("PrivateApi")   // SystemProperties : API cachée d'Android, lue par réflexion : l'app tourne en uid système (voulu).
        val sp  = Class.forName("android.os.SystemProperties")
        val get = sp.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, key, "") as? String)?.takeIf { it.isNotBlank() && it != "0" }
    } catch (_: Exception) { null }
}
