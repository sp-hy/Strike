package com.strike.vehicle

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import com.strike.core.Logs
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.ZipFile

private const val TAG = "BydSdk"
private const val OEM_PKG = "com.byd.data.collect"

/**
 * Load OEM `bydauto` classes by injecting the installed `com.byd.data.collect` APK
 * into this process's ClassLoader — same approach as
 * [Open-DiKey Dilink5SdkInjector](https://github.com/sp-hy/Open-DiKey/blob/main/app/src/main/java/com/sphy/airconcontroller/byd/Dilink5SdkInjector.kt).
 * Never use a child DexClassLoader; BYD binders expect the host app ClassLoader.
 */
object BydSdk {

    private val PROBE_CLASSES = listOf(
        "android.hardware.bydauto.statistic.BYDAutoStatisticDevice",
        "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
        "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice",
        "android.hardware.bydauto.power.BYDAutoPowerDevice",
        "android.hardware.bydauto.ota.BYDAutoOtaDevice",
        "android.hardware.bydauto.ac.BYDAutoAcDevice",
    )

    @Volatile private var permanentlyUnavailable = false
    private var pristineLoader: ClassLoader? = null
    private var pristineDexElements: Array<*>? = null
    private val classes = HashMap<String, Class<*>?>()

    @Synchronized
    fun ensure(context: Context): Boolean {
        val loader = context.classLoader
        if (loadable(loader)) return true
        if (permanentlyUnavailable) return false

        val apkPaths = oemApkPaths(context)
        if (apkPaths.isEmpty()) {
            Logs.w(TAG, "$OEM_PKG not found / no apk path")
            permanentlyUnavailable = true
            return false
        }

        return try {
            val baseCl = Class.forName("dalvik.system.BaseDexClassLoader")
            val pathListF = baseCl.getDeclaredField("pathList").apply { isAccessible = true }
            val pathList = pathListF.get(loader)
            val dexListCls = pathList.javaClass
            val dexElementsF = dexListCls.getDeclaredField("dexElements").apply { isAccessible = true }
            val old = dexElementsF.get(pathList) as Array<*>
            val base = if (pristineLoader === loader) pristineDexElements!! else old.also {
                pristineLoader = loader
                pristineDexElements = it
            }

            val suppressed = ArrayList<Throwable>()
            val newEls = makeInMemoryElements(dexListCls, apkPaths, suppressed)
                ?: makeElements(
                    dexListCls,
                    apkPaths.map { File(it) },
                    File(context.codeCacheDir, "bydauto-inj").apply { mkdirs() },
                    suppressed
                )
                ?: return false.also { Logs.w(TAG, "no dex-element builder found") }
            suppressed.forEach { Logs.w(TAG, "suppressed: $it") }

            val comp = requireNotNull(base.javaClass.componentType) { "dexElements is not an array" }
            val combined = java.lang.reflect.Array.newInstance(comp, base.size + newEls.size)
            System.arraycopy(base, 0, combined, 0, base.size)
            System.arraycopy(newEls, 0, combined, base.size, newEls.size)
            dexElementsF.set(pathList, combined)

            val ok = loadable(loader)
            Logs.d(TAG, "injected ${newEls.size} dex element(s); bydauto loadable=$ok")
            ok
        } catch (t: Throwable) {
            Logs.w(TAG, "inject failed: ${t.javaClass.name}: ${t.message}")
            false
        }
    }

    fun isLoadable(context: Context): Boolean = loadable(context.classLoader)

    @Synchronized
    fun deviceClass(name: String, context: Context): Class<*>? {
        if (classes.containsKey(name)) return classes[name]
        if (!ensure(context) && !isLoadable(context)) {
            classes[name] = null
            return null
        }
        val found = try {
            Class.forName(name)
        } catch (e: ClassNotFoundException) {
            Logs.w(TAG, "$name is not loadable after OEM inject")
            null
        }
        classes[name] = found
        return found
    }

    private fun loadable(loader: ClassLoader): Boolean =
        PROBE_CLASSES.any { runCatching { Class.forName(it, false, loader) }.isSuccess }

    private fun oemApkPaths(context: Context): List<String> = runCatching {
        val ai = context.packageManager.getApplicationInfo(OEM_PKG, 0)
        buildList {
            ai.sourceDir?.let { add(it) }
            ai.splitSourceDirs?.let { addAll(it) }
        }.distinct()
    }.getOrDefault(emptyList())

    private fun makeInMemoryElements(
        dexListCls: Class<*>,
        apkPaths: List<String>,
        suppressed: MutableList<Throwable>
    ): Array<*>? {
        val m = runCatching {
            dexListCls.getDeclaredMethod(
                "makeInMemoryDexElements",
                Array::class.java,
                List::class.java
            ).apply { isAccessible = true }
        }.getOrNull() ?: return null

        val buffers = apkPaths.flatMap { path ->
            runCatching {
                ZipFile(path).use { zip ->
                    zip.entries().asSequence()
                        .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                        .map { entry -> ByteBuffer.wrap(zip.getInputStream(entry).readBytes()) }
                        .toList()
                }
            }.getOrElse { e ->
                suppressed.add(IOException("read $path: ${e.message}", e))
                emptyList()
            }
        }
        if (buffers.isEmpty()) return null
        return m.invoke(null, buffers.toTypedArray(), suppressed) as Array<*>
    }

    private fun makeElements(
        dexListCls: Class<*>,
        files: List<File>,
        optDir: File,
        suppressed: MutableList<Throwable>
    ): Array<*>? {
        runCatching {
            val m = dexListCls.getDeclaredMethod(
                "makePathElements",
                List::class.java,
                File::class.java,
                List::class.java
            ).apply { isAccessible = true }
            return m.invoke(null, files, optDir, suppressed) as Array<*>
        }
        runCatching {
            val m = dexListCls.getDeclaredMethod(
                "makeDexElements",
                List::class.java,
                File::class.java,
                List::class.java,
                ClassLoader::class.java
            ).apply { isAccessible = true }
            return m.invoke(null, files, optDir, suppressed, BydSdk::class.java.classLoader) as Array<*>
        }
        return null
    }
}

/**
 * Client-side bypass for BYD `BYDAUTO_*` checks on getInstance — same idea as
 * [Open-DiKey BydPermissionContext](https://github.com/sp-hy/Open-DiKey/blob/main/app/src/main/java/com/sphy/airconcontroller/byd/BydPermissionContext.kt).
 * Server-side IPC still requires real `pm grant` on the calling package (app UID).
 */
class BydPermissions(base: Context) : ContextWrapper(base) {

    override fun getApplicationContext(): Context = this

    override fun checkSelfPermission(permission: String): Int =
        if (isBydAuto(permission)) PackageManager.PERMISSION_GRANTED
        else super.checkSelfPermission(permission)

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
        if (isBydAuto(permission)) PackageManager.PERMISSION_GRANTED
        else super.checkPermission(permission, pid, uid)

    override fun checkCallingPermission(permission: String): Int =
        if (isBydAuto(permission)) PackageManager.PERMISSION_GRANTED
        else super.checkCallingPermission(permission)

    override fun checkCallingOrSelfPermission(permission: String): Int =
        if (isBydAuto(permission)) PackageManager.PERMISSION_GRANTED
        else super.checkCallingOrSelfPermission(permission)

    override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {
        if (!isBydAuto(permission)) super.enforcePermission(permission, pid, uid, message)
    }

    override fun enforceCallingPermission(permission: String, message: String?) {
        if (!isBydAuto(permission)) super.enforceCallingPermission(permission, message)
    }

    override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
        if (!isBydAuto(permission)) super.enforceCallingOrSelfPermission(permission, message)
    }

    private fun isBydAuto(permission: String): Boolean =
        permission.startsWith("android.permission.BYDAUTO_")
}
