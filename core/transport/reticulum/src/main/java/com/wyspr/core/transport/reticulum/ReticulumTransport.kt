package com.wyspr.core.transport.reticulum

import com.wyspr.core.identity.CommunityId
import com.wyspr.core.transport.Link
import com.wyspr.core.transport.PeerEndpoint
import com.wyspr.core.transport.Transport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Reticulum / LoRa backbone transport — placeholder.
 *
 * Reticulum (reticulum.network) is a network stack that runs over any
 * carrier: LoRa radios, TCP, UDP, Bluetooth, serial USB. ironmesh-style
 * backbone nodes carry traffic over LoRa with long-range, low-bandwidth
 * characteristics, and Reticulum's "Resource" abstraction handles
 * fragmentation below our 16KB Link frame layer.
 *
 * Why a stub today:
 *   - Reticulum doesn't have a maintained Kotlin/JVM client yet — the
 *     reference impl is Python (`rns`). The Android binding will most
 *     likely come via JNI to the C port or via a Tailscale-style
 *     localhost-proxy bridge to a Reticulum daemon on a paired ironmesh
 *     node.
 *   - The Transport contract was designed for this exact use case:
 *     opaque byte channels, length-prefixed framing, no transport-
 *     specific assumptions in the handshake. Wiring will be ~200 lines
 *     once the Kotlin binding exists.
 *
 * Discovery model when implemented:
 *   - Announce on the local Reticulum network using a destination
 *     hash derived from CommunityId (similar to BLE service UUID).
 *   - Discovered peers are Reticulum destination hashes, not BLE MACs.
 *
 * Bandwidth budget on LoRa:
 *   - Noise XX handshake fits in ~256 bytes per message — workable.
 *   - InvitationCertificate is ~300 bytes — workable.
 *   - Sync push frames can be large; the SyncEngine already chunks
 *     into 16KB frames, and Reticulum splits below that automatically.
 *   - Expect handshakes over LoRa to take 10-30 seconds; the
 *     [HandshakeOptions.frameTimeoutMs] cap in core:trust should be
 *     bumped accordingly when this transport is active.
 *
 * Reference for the implementer:
 *   - https://reticulum.network/
 *   - https://github.com/markqvist/Reticulum (Python ref impl)
 *   - https://github.com/markqvist/RNodeConfigUtil (LoRa modem setup)
 */
class ReticulumTransport : Transport {

    override val kind: Transport.Kind = Transport.Kind.Reticulum

    override suspend fun start(communityId: CommunityId) {
        TODO(
            "Reticulum transport is a v0.3 milestone. Today the Kotlin " +
                "bindings don't exist — see NEXT-STEPS.md §3 and " +
                "core/transport/reticulum/README.md.",
        )
    }

    override suspend fun stop() {
        // No-op until start() is wired.
    }

    override fun discovered(): Flow<PeerEndpoint> = emptyFlow()

    override suspend fun connect(endpoint: PeerEndpoint): Link {
        require(endpoint.kind == Transport.Kind.Reticulum)
        TODO("connect() awaits Reticulum binding")
    }
}
