package io.github.kemus

import kotlinx.serialization.Serializable

/**
 * A single entry in a store's change index: key [key] was last mutated at monotonic [version], and
 * is now a tombstone if [deleted] (the key no longer exists). Used by [ChangeSource.changesSince] so
 * a reconnecting device can learn exactly which keys to re-read since it last synced.
 *
 * [hash] is a stable content fingerprint of the key's value (`null` for a tombstone, or when the
 * store was not built with content hashing). Two values are equal iff their hashes match across any
 * kemus store, so a sync can skip keys whose two sides already agree — and compare hashes as an
 * anti-entropy check that does not depend on cursors being intact. See [ChangeSource.changesSince].
 */
@Serializable
data class ChangeEntry(
    val key: String,
    val version: Long,
    val deleted: Boolean,
    val hash: String? = null,
)

/**
 * A page of changes returned by [ChangeSource.changesSince].
 *
 * - [epoch] identifies the store's change-index instance. It is regenerated whenever the index is
 *   rebuilt (e.g. a server restart loses incremental history), so a client whose cursor carries a
 *   different epoch must discard it.
 * - [cursor] is the highest version covered by this page; pass it back as `since` next time.
 * - [resyncRequired] is `true` when the caller's cursor could not be honoured incrementally: an
 *   epoch mismatch, change-tracking disabled, or a cursor older than deletions that have since been
 *   compacted away. In that case [changes] already contains the full current keyspace (as if
 *   `since = 0`), so the client should treat every entry as changed and adopt the returned
 *   [epoch]/[cursor].
 */
@Serializable
data class ChangesPage(
    val epoch: String,
    val cursor: Long,
    val resyncRequired: Boolean,
    val changes: List<ChangeEntry>,
)

/**
 * A store that can report which keys changed since a cursor — the pull side of offline→online sync.
 * Implemented by the embedded engine ([Kemus], when constructed with change-tracking) and by the
 * remote client (`KemusClient`), so a device can diff its local store against a server uniformly.
 */
interface ChangeSource {
    /**
     * Return the keys changed after [since] (exclusive). On the first call pass `since = 0` and a
     * `null` [epoch] to receive the whole keyspace; on later calls pass the previous
     * [ChangesPage.cursor] and [ChangesPage.epoch]. See [ChangesPage] for the resync contract.
     *
     * [limit] caps the page size (`<= 0` means unlimited). When a page is capped and more remain, its
     * [ChangesPage.cursor] is the last version it covers — pull again from there. A page smaller than
     * [limit] (or any unlimited page) means the caller has caught up to the high-water mark.
     */
    suspend fun changesSince(since: Long, epoch: String? = null, limit: Int = 0): ChangesPage
}
