package com.wyspr.core.trust

import com.goterl.lazysodium.LazySodiumAndroid
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.transport.Link
import kotlinx.coroutines.flow.firstOrNull

suspend fun runKeyRotationSyncRound(
    link: Link,
    database: WysprDatabase,
    communityId: ByteArray,
    trustGraph: TrustGraph?,
    sodium: LazySodiumAndroid,
): Int {
    val repository = KeyRotationSyncRepository(communityId)

    val myHave = repository.haveSet(database)
    link.sendMessage(KeyRotationSyncMessage.HaveSet(myHave))

    val peerHave = link.receiveMessage<KeyRotationSyncMessage.HaveSet>()

    val want = repository.want(database, peerHave.pairs)
    link.sendMessage(KeyRotationSyncMessage.Want(want))

    val peerWant = link.receiveMessage<KeyRotationSyncMessage.Want>()

    val push = repository.fetch(database, peerWant.pairs)
    link.sendMessage(KeyRotationSyncMessage.Push(push))

    val peerPush = link.receiveMessage<KeyRotationSyncMessage.Push>()
    return repository.resolveAndIngestBatch(
        database, peerPush.certs, trustGraph, sodium,
    )
}

private suspend fun Link.sendMessage(msg: KeyRotationSyncMessage) {
    val bytes = KeyRotationSyncMessage.encode(msg)
    require(bytes.size <= KeyRotationSyncMessage.MAX_FRAME_BYTES) {
        "Key rotation sync message too large: ${bytes.size} > " +
            "${KeyRotationSyncMessage.MAX_FRAME_BYTES}"
    }
    send(bytes)
}

@Suppress("UNCHECKED_CAST")
private suspend inline fun <reified T : KeyRotationSyncMessage> Link.receiveMessage(): T {
    val frame = incoming().firstOrNull()
        ?: error("Peer closed the link before sending the expected key rotation message")
    val decoded = KeyRotationSyncMessage.decode(frame)
    require(decoded is T) {
        "Expected ${T::class.simpleName}, got ${decoded::class.simpleName}"
    }
    return decoded as T
}
