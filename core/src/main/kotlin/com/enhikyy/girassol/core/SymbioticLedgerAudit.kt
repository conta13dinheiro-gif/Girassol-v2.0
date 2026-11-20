package com.enhikyy.girassol.core

import kotlin.math.abs

/** Streaming verification binds each new learned module to the preceding canonical SQLite event. */
class SymbioticLedgerAudit {
    private var previousHash: String?=null
    private var previousMinds=emptyMap<String,MindState>()
    fun accept(event: CanonicalEvent) {
        event.cognition?.let { frame ->
            if(frame.biologyEnabled && event.tick>1) check(frame.sourceHash==previousHash) { "Biology source mismatch" }
            frame.minds.forEach { (id,mind) ->
                val old=previousMinds[id]
                mind.symbioticModules.filter { it !in old?.symbioticModules.orEmpty() }.forEach { module ->
                    check(frame.biologyEnabled && old!=null && module.valid(event.tick-1) && module.tick==event.tick-1 && module.sourceHash==previousHash)
                    val donor=checkNotNull(previousMinds[module.donor]) { "Missing symbiotic donor" }
                    val trace=donor.memory.learningFor(module.action)
                    check(module.donorExecutions==trace.verifiedExecutions && abs(module.donorKnowledge-trace.knowledgeProbability)<1e-12 &&
                        abs(module.recipientKnowledge-old.memory.learningFor(module.action).knowledgeProbability)<1e-12)
                    check(donor.memory.skills.any { it.action==module.action } &&
                        module.donorBlockHash==PedagogyAndDiploidEvolutionEngine.blockHash(donor.parameters,module.action) &&
                        module.beforeHash==PedagogyAndDiploidEvolutionEngine.blockHash(old.parameters,module.action))
                    val expected=old.parameters.toMutableList();val start=184+module.action.ordinal*8;val blend=.25*module.trust
                    repeat(8) { i -> expected[start+i]=((1-blend)*old.parameters[start+i]+blend*donor.parameters[start+i]).coerceIn(-8.0,8.0) }
                    check(module.afterHash==PedagogyAndDiploidEvolutionEngine.blockHash(expected,module.action)) { "Symbiotic fusion digest mismatch" }
                }
            }
            previousMinds=frame.minds
        }
        previousHash=event.stateHash
    }
}
