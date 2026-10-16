package com.metavitae.soundlab

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONObject

/**
 * Hive mode link (founder 2026-10-15: full hive with Chat's screens; "all options": sound + Bluetooth + Wi-Fi).
 * Android's nearby-devices link (Nearby Connections, P2P_CLUSTER) finds the other Batsight phones and carries small
 * JSON messages between them over Bluetooth or direct Wi-Fi, whichever works; nothing goes to the internet.
 * WHICH phone is which, and how far apart, is settled by the SOUND handshake (Join screen), not by the radio.
 * Each phone makes a random token; sorted tokens give the name tags A, B, C.
 */
class Hive(private val ctx: Context, private val onEvent: (String, JSONObject) -> Unit) {
    companion object { const val SERVICE = "com.metavitae.batsight.hive" }

    val token: Long = (Math.random() * Long.MAX_VALUE).toLong()
    private val client = Nearby.getConnectionsClient(ctx)
    private val peers = LinkedHashMap<String, Long>()          // endpoint id -> token
    @Volatile var running = false; private set

    fun peerCount() = synchronized(peers) { peers.size }

    /** A, B, C... by token order among this phone and its connected peers. */
    fun myTag(): String {
        val all = synchronized(peers) { peers.values.toMutableList() }.apply { add(token) }.sorted()
        return ('A' + all.indexOf(token)).toString()
    }

    fun tagOf(endpoint: String): String? {
        val t = synchronized(peers) { peers[endpoint] } ?: return null
        val all = synchronized(peers) { peers.values.toMutableList() }.apply { add(token) }.sorted()
        return ('A' + all.indexOf(t)).toString()
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(ep: String, p: Payload) {
            val b = p.asBytes() ?: return
            val j = try { JSONObject(String(b)) } catch (_: Throwable) { return }
            if (j.optString("t") == "hello") { synchronized(peers) { peers[ep] = j.optLong("token") }; onEvent("peers", status()); return }
            j.put("from", tagOf(ep) ?: "?")
            onEvent("msg", j)
        }
        override fun onPayloadTransferUpdate(ep: String, u: PayloadTransferUpdate) {}
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(ep: String, info: ConnectionInfo) { client.acceptConnection(ep, payloads) }
        override fun onConnectionResult(ep: String, r: ConnectionResolution) {
            if (r.status.isSuccess) {
                client.sendPayload(ep, Payload.fromBytes(JSONObject().put("t", "hello").put("token", token).toString().toByteArray()))
            }
            onEvent("peers", status())
        }
        override fun onDisconnected(ep: String) { synchronized(peers) { peers.remove(ep) }; onEvent("peers", status().put("dropped", true)) }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(ep: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE) return
            // the phone with the larger token asks, so two phones don't both ask at once
            val theirs = info.endpointName.toLongOrNull() ?: 0L
            if (token > theirs) client.requestConnection(token.toString(), ep, lifecycle)
        }
        override fun onEndpointLost(ep: String) {}
    }

    fun status(): JSONObject = JSONObject().put("me", myTag()).put("peers", peerCount()).put("tags", synchronized(peers) { peers.keys.mapNotNull { tagOf(it) } }.joinToString(","))

    fun start() {
        if (running) return
        running = true
        val s = Strategy.P2P_CLUSTER
        client.startAdvertising(token.toString(), SERVICE, lifecycle, AdvertisingOptions.Builder().setStrategy(s).build())
            .addOnFailureListener { onEvent("error", JSONObject().put("where", "advertise").put("why", it.message)) }
        client.startDiscovery(SERVICE, discovery, DiscoveryOptions.Builder().setStrategy(s).build())
            .addOnFailureListener { onEvent("error", JSONObject().put("where", "discover").put("why", it.message)) }
    }

    fun send(j: JSONObject) {
        val eps = synchronized(peers) { peers.keys.toList() }
        if (eps.isNotEmpty()) client.sendPayload(eps, Payload.fromBytes(j.toString().toByteArray()))
    }

    fun stop() {
        running = false
        client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints()
        synchronized(peers) { peers.clear() }
    }
}
