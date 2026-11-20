package com.enhikyy.girassol.core

/** Streaming provenance check against actual earlier action receipts, not optimizer self-labels. */
class LoraLedgerAudit {
    private var previous: CanonicalEvent?=null
    private var minds=emptyMap<String,MindState>()
    fun accept(event: CanonicalEvent) {
        check(minds.values.none { it.lora!=null } || event.cognition!=null) { "Active adapter lost cognitive input" }
        event.cognition?.minds?.forEach { (id,mind) -> mind.lora?.let { next ->
            check(next.valid(event.tick-1))
            val old=minds[id]?.lora
            if(old==null) check(next.steps()==0L) { "LoRA training without initialized source" }
            else if(next!=old) {
                val source=checkNotNull(previous)
                check(next.steps()==old.steps()+1 && next.trainedTick==source.tick && source.tick>old.trainedTick && next.sourceHash==source.stateHash)
                check(next.baseHash==LoraAdapterState.baseHash(checkNotNull(minds[id])))
                check(source.actions.any { it.agentId==id && it.outcome=="OK" }) { "LoRA training without actual successful receipt" }
            }
        } }
        event.cognition?.let { frame ->
            check(minds.all { (id,mind) -> mind.lora==null || id !in frame.minds || frame.minds[id]?.lora!=null }) { "Adapter discarded" }
            minds=frame.minds
        }
        previous=event
    }
}
