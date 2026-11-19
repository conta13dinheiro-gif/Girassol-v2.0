package com.enhikyy.girassol.core

/** Streaming envelope audit, not a replacement for deterministic WorldActor.replay. */
class JournalAudit {
    private var count = 0L
    private var hash = WorldActor.GENESIS_HASH
    private var stateHash: String? = null
    fun accept(rowId: Long, rowTick: Long, event: CanonicalEvent) {
        check(event.valid()) { "Hash inválido no evento $rowId" }
        check(rowId == count + 1 && rowTick == rowId && event.eventId == rowId && event.tick == rowTick) {
            "Sequência inválida no evento $rowId"
        }
        check(event.previousHash == hash) { "Cadeia interrompida no evento $rowId" }
        count = rowId; hash = event.hash; stateHash = event.stateHash
    }
    fun finish(checkpoint: Checkpoint?): Long {
        if (checkpoint == null) check(count == 0L) { "Checkpoint ausente" }
        else {
            check(count == checkpoint.eventId && count == checkpoint.state.tick && hash == checkpoint.eventHash) { "Cauda divergente do checkpoint" }
            check(stateHash == Canonical.hash(Canonical.state(checkpoint.state))) { "Estado divergente do checkpoint" }
        }
        return count
    }
    companion object {
        fun validateTransition(t: Transition) {
            val e=t.event
            check(e.valid() && e.eventId>0 && e.eventId==e.tick && e.tick==t.state.tick) { "Identidade da transição inválida" }
            check(WorldActor.violations(t.state).isEmpty()) { "Estado viola os contratos do mundo" }
            check(e.stateHash==Canonical.hash(Canonical.state(t.state))) { "Digest da transição inválido" }
        }
        fun validateTail(cp: Checkpoint, event: CanonicalEvent) {
            check(event.valid() && event.eventId==cp.eventId && event.tick==cp.state.tick &&
                event.hash==cp.eventHash && event.stateHash==Canonical.hash(Canonical.state(cp.state))) { "Âncora do checkpoint inválida" }
        }
    }
}
