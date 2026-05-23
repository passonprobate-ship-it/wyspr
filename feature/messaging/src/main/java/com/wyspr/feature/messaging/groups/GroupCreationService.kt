package com.wyspr.feature.messaging.groups

import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.database.entities.GroupEntity
import com.wyspr.core.database.entities.GroupMemberEntity
import com.wyspr.core.identity.GroupId
import com.wyspr.core.identity.PublicKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single-purpose service for the create-group flow. Derives the
 * `groupId`, signs N `GroupMembership` certs (including the creator's
 * own), and persists everything via [GroupStore]. After this returns
 * the group is fully visible on the local device; sending the certs
 * to the new members happens on the next sync round (the certs are
 * stored in `group_member` and the sync engine — once we extend it
 * in Phase 3+ — propagates them on its Push frame).
 *
 * v1 invariants enforced here:
 * - The local device's identity MUST be the creator. We won't sign
 *   a membership cert as someone else.
 * - The member list always includes the creator. UI may pass them
 *   in or omit; we ensure they're there either way.
 */
@Singleton
class GroupCreationService @Inject constructor(
    private val keystore: KeystoreManager,
    private val groupStore: GroupStore,
) {

    /**
     * Build and persist a fresh group with the given members. Returns
     * the new group's id so the caller can navigate straight into it.
     *
     * @param name user-chosen group name. Cannot be blank.
     * @param otherMembers pubkeys of every peer besides the creator
     *                     who should be in the group at creation time.
     * @param nowSeconds   sender clock; same wall-clock used by the
     *                     rest of the messaging path so signed `t`
     *                     values are consistent.
     */
    suspend fun createGroup(
        name: String,
        otherMembers: List<PublicKey>,
        nowSeconds: Long,
    ): GroupId {
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty()) { "group name must not be blank" }
        require(otherMembers.isNotEmpty()) {
            "a group must have at least one other member"
        }

        val identity = keystore.loadOrCreateIdentityKey()
        val creatorPub = PublicKey(identity.publicKey)
        val createdAt = nowSeconds
        val groupId = GroupId.derive(creatorPub, trimmedName, createdAt)

        // Build the member list: creator first, then every distinct
        // other-member. Dedupe in case the UI passed the creator in
        // explicitly or repeated a peer.
        val allMembers = mutableListOf<PublicKey>()
        allMembers.add(creatorPub)
        for (m in otherMembers) {
            val alreadyIn = allMembers.any { existing ->
                existing.bytes.contentEquals(m.bytes)
            }
            if (!alreadyIn) allMembers.add(m)
        }

        val certs = allMembers.map { member ->
            GroupMembership.issue(
                keystore = keystore,
                groupId = groupId,
                memberPub = member,
                name = trimmedName,
                createdAt = createdAt,
                issuerPub = creatorPub,
                issuedAt = nowSeconds,
            )
        }

        val groupEntity = GroupEntity(
            groupId = groupId.bytes,
            name = trimmedName,
            creatorPub = creatorPub.bytes,
            createdAt = createdAt,
            localNickname = null,
        )
        val memberEntities = certs.map { cert ->
            GroupMemberEntity(
                groupId = groupId.bytes,
                memberPub = cert.memberPub.bytes,
                certBytes = cert.wireBytes(),
                addedAt = cert.issuedAt,
                status = GroupMemberEntity.STATUS_ACTIVE,
            )
        }

        groupStore.ensureGroup(groupEntity, memberEntities)
        return groupId
    }
}
