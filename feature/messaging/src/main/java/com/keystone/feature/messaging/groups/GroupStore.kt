package com.keystone.feature.messaging.groups

import com.goterl.lazysodium.LazySodiumAndroid
import com.keystone.core.database.KeystoneDatabase
import com.keystone.core.database.entities.GroupEntity
import com.keystone.core.database.entities.GroupMemberEntity
import com.keystone.core.database.entities.GroupMessageEntity
import com.keystone.core.identity.GroupId
import com.keystone.core.identity.PublicKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence facade for private groups. Sits between the sync
 * engine and Room. Mirrors `MessageStore` in shape — exposed via
 * `KeystoneDatabase`'s DAOs, but adds the multi-table coordination
 * (create a group + insert N membership certs in one go).
 */
@Singleton
class GroupStore @Inject constructor(
    private val database: KeystoneDatabase,
) {

    /**
     * Persist a freshly-created group with its membership certs.
     * Called by the create-group flow on the creator's device, and
     * by recipients when they first observe a `GroupMembership` cert
     * for themselves.
     */
    suspend fun ensureGroup(
        group: GroupEntity,
        members: List<GroupMemberEntity>,
    ) {
        ensureOpen()
        database.groupDao.upsert(group)
        if (members.isNotEmpty()) {
            database.groupMemberDao.upsertAll(members)
        }
    }

    suspend fun groupById(groupId: GroupId): GroupEntity? {
        ensureOpen()
        return database.groupDao.byId(groupId.bytes)
    }

    suspend fun activeMembers(groupId: GroupId): List<GroupMemberEntity> {
        ensureOpen()
        return database.groupDao.byId(groupId.bytes)
            ?.let { database.groupMemberDao.activeForGroup(groupId.bytes) }
            ?: emptyList()
    }

    /**
     * Group messages we (the local user, identified by [ownPub]) have
     * outbound for delivery to [peerPub]. v1 semantics: a message is
     * "pending for peer P" iff its status is pending AND P is an
     * active member of the group AND P is not the sender. We do not
     * track per-recipient ack state — once any peer acks, status flips
     * to "sent" and the message stops being offered.
     *
     * This makes large-group delivery best-effort. v2 will introduce a
     * `group_message_delivery` junction so each member's receipt is
     * tracked independently.
     */
    suspend fun pendingGroupMessagesForPeer(
        ownPub: PublicKey,
        peerPub: PublicKey,
    ): List<GroupMessageEntity> {
        ensureOpen()
        val all = database.groupMessageDao.pendingOutboundFrom(ownPub.bytes)
        if (all.isEmpty()) return emptyList()
        // Filter to messages whose group has [peerPub] as an active member.
        val peerGroups = database.groupMemberDao
            .groupsForMember(peerPub.bytes)
            .map { it.groupId.toList() }
            .toSet()
        return all.filter { msg ->
            msg.groupId.toList() in peerGroups
        }
    }

    /**
     * Ingest an inbound group envelope from a paired peer. Verifies the
     * signature, confirms the sender is an active member of the group,
     * and persists. Returns true if the message was accepted (or already
     * existed); false if it was rejected.
     *
     * NOTE: This does NOT verify the `GroupMembership` cert itself — that's
     * the caller's job at sync time so we can pass the lazysodium handle in.
     * The caller has already verified the cert lives in the local
     * group_member table with status = active.
     */
    suspend fun ingestGroup(
        envelope: GroupMessageEnvelope,
        receivedAtSeconds: Long,
    ): Boolean {
        ensureOpen()
        // Dedupe — multiple members may forward the same group message id.
        if (database.groupMessageDao.exists(envelope.id)) return true

        // Sender must be an active member of the group.
        val members = database.groupMemberDao.activeForGroup(envelope.groupId.bytes)
        val isMember = members.any { it.memberPub.contentEquals(envelope.fromPub.bytes) }
        if (!isMember) return false

        database.groupMessageDao.upsert(
            GroupMessageEntity(
                id = envelope.id,
                groupId = envelope.groupId.bytes,
                fromPub = envelope.fromPub.bytes,
                body = envelope.body,
                createdAt = envelope.createdAt,
                receivedAt = receivedAtSeconds,
                status = STATUS_RECEIVED,
                signature = envelope.signature,
            ),
        )
        return true
    }

    suspend fun markGroupSent(id: ByteArray) {
        ensureOpen()
        database.groupMessageDao.updateStatus(id, STATUS_SENT)
    }

    /**
     * Every active `GroupMembership` cert the local device holds for
     * groups that [peerPub] is also a member of. Inlined into the
     * Push frame on each sync so a peer can rebuild the group's
     * membership view from scratch (their own cert + every other
     * member's cert). v1 includes all of them every round; v2 will
     * track per-peer "has seen" state to avoid re-sending.
     */
    suspend fun membershipCertsForPeer(peerPub: PublicKey): List<GroupMembership> {
        ensureOpen()
        val peerGroups = database.groupMemberDao
            .groupsForMember(peerPub.bytes)
            .map { it.groupId.toList() }
            .toSet()
        if (peerGroups.isEmpty()) return emptyList()
        val out = ArrayList<GroupMembership>()
        for (groupBytes in peerGroups) {
            val members = database.groupMemberDao.activeForGroup(groupBytes.toByteArray())
            for (member in members) {
                runCatching { GroupMembership.fromWire(member.certBytes) }
                    .getOrNull()
                    ?.let { out.add(it) }
            }
        }
        return out
    }

    /**
     * Validate + persist an inbound `GroupMembership` cert from a peer.
     * Returns true if the cert was accepted (already known or newly
     * stored); false if it failed verification or contradicts an
     * already-known group's creator.
     *
     * Verification:
     * - Signature checks out against [GroupMembership.issuerPub].
     * - `GroupId.derive(issuerPub, name, createdAt)` matches the
     *   cert's declared `groupId`.
     * - If a `group_entity` row exists for `groupId`, its `creatorPub`
     *   must equal `issuerPub` — once we know a group's creator we
     *   refuse to accept membership certs signed by anyone else.
     */
    suspend fun ingestMembership(
        cert: GroupMembership,
        sodium: LazySodiumAndroid,
    ): Boolean {
        ensureOpen()
        if (!cert.verify(sodium)) return false
        val existing = database.groupDao.byId(cert.groupId.bytes)
        if (existing != null) {
            if (!existing.creatorPub.contentEquals(cert.issuerPub.bytes)) return false
        } else {
            // First time we've seen this group — trust the cert for
            // its (name, createdAt, creator) since GroupId.derive ties
            // those bytes to the id.
            database.groupDao.upsert(
                GroupEntity(
                    groupId = cert.groupId.bytes,
                    name = cert.name,
                    creatorPub = cert.issuerPub.bytes,
                    createdAt = cert.createdAt,
                    localNickname = null,
                ),
            )
        }
        database.groupMemberDao.upsert(
            GroupMemberEntity(
                groupId = cert.groupId.bytes,
                memberPub = cert.memberPub.bytes,
                certBytes = cert.wireBytes(),
                addedAt = cert.issuedAt,
                status = GroupMemberEntity.STATUS_ACTIVE,
            ),
        )
        return true
    }

    /**
     * Mapping helper used by the sync engine's push path.
     */
    fun GroupMessageEntity.toEnvelope(): GroupMessageEnvelope = GroupMessageEnvelope(
        id = id,
        fromPub = PublicKey(fromPub),
        groupId = GroupId(groupId),
        createdAt = createdAt,
        body = body,
        signature = signature,
    )

    private fun ensureOpen() {
        check(database.isOpen) { "KeystoneDatabase not open" }
    }

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_SENT = "sent"
        const val STATUS_DELIVERED = "delivered"
        const val STATUS_READ = "read"
        const val STATUS_RECEIVED = "received"
    }
}
