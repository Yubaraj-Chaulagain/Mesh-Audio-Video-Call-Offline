package com.example.meshcall

import java.util.concurrent.ConcurrentHashMap

/** Lightweight routing/heartbeat state. Media forwarding requires a negotiated relay transport. */
class MeshRouter {
    data class Node(val id: String, val address: String, val seenAt: Long, val hops: Int = 1)
    private val nodes = ConcurrentHashMap<String, Node>()
    fun seen(id: String, address: String, hops: Int = 1) { nodes[id] = Node(id, address, System.currentTimeMillis(), hops) }
    fun remove(id: String) { nodes.remove(id) }
    fun live(maxAgeMs: Long = 8000): List<Node> = nodes.values.filter { System.currentTimeMillis() - it.seenAt <= maxAgeMs }.sortedBy { it.hops }
    fun bestRoute(): Node? = live().minByOrNull { it.hops }
    fun prune(maxAgeMs: Long = 8000) { nodes.entries.removeIf { System.currentTimeMillis() - it.value.seenAt > maxAgeMs } }
}
