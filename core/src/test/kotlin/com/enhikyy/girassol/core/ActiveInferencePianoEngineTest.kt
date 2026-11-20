package com.enhikyy.girassol.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveInferencePianoEngineTest {
    @Test
    fun prioritizesHydrationWhenHydrationIsLow() {
        val result = ActiveInferencePianoEngine.evaluate(
            needs = ActiveInferenceNeeds(.10, .86, .86, .86, .60),
            novelty = .4, predictionError = .1, openness = .5, dopamine = .5,
            previousReward = 0.0, thermalScale = 1.0, currentActivity = Activity.STUDY
        )
        assertTrue(result.utilityByActivity.getValue(Activity.WATER) >
            result.utilityByActivity.getValue(Activity.TALK))
    }

    @Test
    fun explorationValueIsReducedByThermalBudget() {
        val cool = ActiveInferencePianoEngine.evaluate(
            ActiveInferenceNeeds(.8, .8, .8, .8, .1), .9, .1, .9, .8,
            0.0, 1.0, Activity.SLEEP
        )
        val hot = ActiveInferencePianoEngine.evaluate(
            ActiveInferenceNeeds(.8, .8, .8, .8, .1), .9, .1, .9, .8,
            0.0, 0.0, Activity.SLEEP
        )
        assertTrue(cool.utilityByActivity.getValue(Activity.STUDY) >
            hot.utilityByActivity.getValue(Activity.STUDY))
        assertTrue(hot.utilityByActivity.values.all { it.isFinite() })
    }

    @Test
    fun exposesBoundedGlobalWorkspaceAndStableSelfAnchor() {
        val result = ActiveInferencePianoEngine.evaluate(
            ActiveInferenceNeeds(.7, .7, .7, .7, .7), .5, .2, .6, .5,
            .1, .8, Activity.TALK
        )
        assertEquals(3, result.globalWorkspaceTopThree.size)
        assertEquals(3, result.globalWorkspaceTopThree.map { it.activity }.distinct().size)
        assertEquals(CoreSelfAnchor.ID, result.coreSelfAnchorId)
        assertTrue(result.variationalFreeEnergy.isFinite())
    }
}
