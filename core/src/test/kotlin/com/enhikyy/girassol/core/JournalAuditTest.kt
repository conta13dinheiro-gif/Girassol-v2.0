package com.enhikyy.girassol.core

import org.junit.Assert.*
import org.junit.Test

class JournalAuditTest {
    private fun reject(action: () -> Unit) {
        try { action(); fail("Invalid journal accepted") } catch (_: IllegalStateException) { }
    }
    @Test fun completeChainMatchesCheckpointAndEmptyWorldIsAllowed() {
        val actor=WorldActor(); val audit=JournalAudit()
        repeat(100) { val t=actor.step(); audit.accept(t.event.eventId,t.event.tick,t.event) }
        assertEquals(100L,audit.finish(actor.checkpoint()))
        assertEquals(0L,JournalAudit().finish(null))
    }
    @Test fun gapsDuplicatesAndSqlEnvelopeMismatchAreRejected() {
        val actor=WorldActor(); val first=actor.step().event; val second=actor.step().event
        reject { JournalAudit().accept(2,2,second) }
        reject { JournalAudit().accept(1,2,first) }
        reject { JournalAudit().accept(1,1,second) }
        val a=JournalAudit();a.accept(1,1,first);reject { a.accept(1,1,first) }
    }
    @Test fun alteredMiddleEventAndRehashedBrokenLinkAreRejected() {
        val actor=WorldActor();val first=actor.step().event;val second=actor.step().event
        val a=JournalAudit();a.accept(1,1,first)
        reject { a.accept(2,2,second.copy(stateHash="wrong")) }
        val raw=second.copy(previousHash="wrong")
        reject { a.accept(2,2,raw.copy(hash=raw.computedHash())) }
        a.accept(2,2,second)
        assertEquals(2L,a.finish(actor.checkpoint()))
    }
    @Test fun missingCheckpointOrTailCannotPass() {
        val actor=WorldActor();val t=actor.step();val a=JournalAudit();a.accept(1,1,t.event)
        reject { a.finish(null) };reject { JournalAudit().finish(actor.checkpoint()) }
        reject { a.finish(actor.checkpoint().copy(eventHash="bad")) }
    }
    @Test fun invalidButCorrectlyHashedStateCannotBeAdmitted() {
        val actor=WorldActor();val t=actor.step()
        JournalAudit.validateTransition(t)
        val state=t.state.copy(balances=t.state.balances+("market/water" to -1))
        val raw=t.event.copy(stateHash=Canonical.hash(Canonical.state(state)))
        reject { JournalAudit.validateTransition(Transition(state,raw.copy(hash=raw.computedHash()))) }
        val wrong=t.event.copy(eventId=2)
        reject { JournalAudit.validateTail(actor.checkpoint(),wrong.copy(hash=wrong.computedHash())) }
        reject { JournalAudit.validateTransition(t.copy(event=wrong.copy(hash=wrong.computedHash()))) }
    }
}
