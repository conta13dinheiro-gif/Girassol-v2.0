package com.enhikyy.girassol.core

import kotlin.math.ln

/** Exact enumeration of a two-state, two-observation generative model per policy.
 * State is physiological readiness; observation is successful completion. The two
 * likelihood rows are complementary. Preferred outcome probability is 0.95.
 */
object ActiveInferencePianoEngine {
    data class Evidence(val freeEnergy: Double, val expectedFreeEnergy: Double, val success: Double, val pragmatic: Double, val epistemic: Double)
    data class Selection(val action: Activity, val topThree: List<Activity>, val evidence: List<Evidence>)
    private fun kl(p: Double, q: Double) = p*ln(p/q)+(1-p)*ln((1-p)/(1-q))
    private fun entropy(p: Double) = -p*ln(p)-(1-p)*ln(1-p)
    fun evidence(readiness: Double, likelihood: Double): Evidence {
        require(readiness.isFinite() && readiness in 0.0..1.0 && likelihood.isFinite() && likelihood in 0.0..1.0)
        val prior=readiness.coerceIn(.001,.999)
        val l=likelihood.coerceIn(.001,.999)
        val success=prior*l+(1-prior)*(1-l)
        val posterior=prior*l/success
        val f=kl(posterior,prior)-posterior*ln(l)-(1-posterior)*ln(1-l)
        val pragmatic=-success*ln(.95)-(1-success)*ln(.05)
        val epistemic=entropy(success)-entropy(l)
        val g=pragmatic-epistemic
        return Evidence(f,g,success,pragmatic,epistemic)
    }
    fun select(citizen: Citizen, scores: List<Double>, likelihoods: List<Double>): Selection {
        require(scores.size==8 && scores.all(Double::isFinite) && likelihoods.size==8)
        val evidence=likelihoods.map { evidence(citizen.needs.energy,it) }
        val ranked=Activity.entries.sortedWith(compareBy<Activity> {
            val e=evidence[it.ordinal]
            e.expectedFreeEnergy+.25*e.freeEnergy-scores[it.ordinal] -
                if(it==citizen.activity) .05 else 0.0
        }.thenBy { it.ordinal })
        // PSelf homeostatic anchor constrains the workspace before policy execution.
        val anchor=CoreSelfAnchor.urgent(citizen)
        val top=(listOfNotNull(anchor)+ranked).distinct().take(3)
        return Selection(top.first(),top,evidence)
    }
}

object CoreSelfAnchor {
    fun urgent(citizen: Citizen): Activity? = when {
            citizen.needs.hydration<.15 -> Activity.WATER
            citizen.needs.glucose<.15 -> Activity.EAT
            citizen.needs.energy<.15 -> Activity.SLEEP
            else -> null
        }
}
