package com.strike.cloud

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val INTERVAL_MS = 15_000L
private const val WAKE_CALL_MS = 6_000
private const val FORGET_SESSION_AFTER = 3

/** Start-to-start cadence of Overdrive's DiLink 5 cloud keep-alive; failures retry after 1, 3, then 5 s. */
internal fun heartbeatDelayMs(failures: Int, elapsedMs: Long): Long {
    val cadence = (INTERVAL_MS - elapsedMs).coerceAtLeast(0L)
    if (failures == 0) return cadence
    val retry = when (failures) {
        1 -> 1_000L
        2 -> 3_000L
        else -> 5_000L
    }
    return minOf(retry, cadence)
}

/** Keeps asking BYD's cloud for live data while parked so BYD keeps waking the car's T-Box. */
internal class CloudHeartbeat(
    private val tables: () -> ByteArray?,
    private val load: () -> CloudAccount? = { CloudStore.load() },
    private val log: (String) -> Unit
) {

    @Volatile private var worker: Thread? = null
    @Volatile private var lastOkAtMs = 0L
    @Volatile private var failures = 0
    @Volatile private var problem: String? = null
    private var codec: Bangcle? = null
    private var refusedAccount: String? = null

    @Synchronized
    fun want(on: Boolean) {
        val running = worker?.isAlive == true
        if (on && !running) start()
        if (!on && running) {
            worker?.interrupt()
            worker = null
            log("BYD cloud heartbeat stopped")
        }
    }

    fun status(): JSONObject = JSONObject()
        .put("active", worker?.isAlive == true)
        .put("failures", failures)
        .put("lastOkAgeS", if (lastOkAtMs == 0L) JSONObject.NULL else (System.currentTimeMillis() - lastOkAtMs) / 1000)
        .put("problem", problem ?: JSONObject.NULL)

    private fun start() {
        val account = load()
        if (account == null || account.vin.isEmpty()) {
            problem = "No BYD account is signed in"
            return
        }
        if (account.toJson() == refusedAccount) return
        val cipher = codec ?: try {
            tables()?.let { Bangcle(Bangcle.parse(it)) }
        } catch (e: IOException) {
            null
        }
        if (cipher == null) {
            problem = "The BYD cipher tables are missing from this build"
            return
        }
        codec = cipher
        refusedAccount = null
        failures = 0
        log("BYD cloud heartbeat started")
        worker = Thread({ run(account, cipher) }, "cloud-heartbeat").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun run(account: CloudAccount, cipher: Bangcle) {
        val client = CloudClient(account, cipher)
        while (!Thread.currentThread().isInterrupted) {
            val started = System.nanoTime()
            try {
                client.wake(account.vin, WAKE_CALL_MS)
                if (failures > 0 || lastOkAtMs == 0L) log("BYD cloud heartbeat reached the car")
                failures = 0
                problem = null
                lastOkAtMs = System.currentTimeMillis()
            } catch (e: CloudRefused) {
                if (e.signIn && !e.busy) {
                    synchronized(this) { refusedAccount = account.toJson() }
                    problem = "BYD refused the sign-in (${e.code}); sign in again in Settings"
                    log(problem!!)
                    return
                }
                failed(client, "BYD refused the request (${e.code})")
            } catch (e: IOException) {
                failed(client, e.message ?: "BYD could not be reached")
            } catch (e: JSONException) {
                failed(client, "BYD sent an unexpected reply")
            }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            try {
                Thread.sleep(heartbeatDelayMs(failures, elapsed))
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun failed(client: CloudClient, reason: String) {
        failures++
        problem = reason
        if (failures == 1) log("BYD cloud heartbeat failed: $reason")
        if (failures % FORGET_SESSION_AFTER == 0) client.forget()
    }
}
