package com.enhikyy.girassol.audio

import com.enhikyy.girassol.core.*
import org.junit.Assert.*
import org.junit.Test

class AcousticSceneTest {
    @Test fun movementMapsToStereoAndStationaryCitizensDoNotProduceSteps() {
        val world=WorldActor.genesis(); val a=world.agents.first()
        val previous=world.copy(agents=listOf(a.copy(x=6.0,z=6.0)))
        val left=AcousticScene.from(world.copy(tick=1,agents=listOf(a.copy(x=0.0,z=12.0))),previous)
        val right=AcousticScene.from(world.copy(tick=1,agents=listOf(a.copy(x=12.0,z=0.0))),previous)
        assertEquals(1f,left.left,0f); assertEquals(0f,left.right,0f)
        assertEquals(0f,right.left,0f); assertEquals(1f,right.right,0f)
        val stationary=AcousticScene.from(previous.copy(tick=1,agents=listOf(a.copy(x=6.0,z=6.0,acting=false))),previous)
        assertEquals(0f,stationary.left,0f); assertEquals(0f,stationary.right,0f)
    }
    @Test fun firstOrRepeatedSnapshotAndDifferentWorldDoNotInventMovement() {
        val world=WorldActor.genesis()
        val next=world.copy(tick=1,agents=world.agents.map { it.copy(x=it.x+1) })
        for (s in listOf(AcousticScene.from(next),AcousticScene.from(next,next),AcousticScene.from(next,world.copy(seed=99)))) {
            assertEquals(0f,s.left,0f); assertEquals(0f,s.right,0f)
        }
    }
    @Test fun parametersReflectSkillAndActualSocialActivityWithoutChangingState() {
        val original=WorldActor.genesis()
        val world=original.copy(agents=original.agents.mapIndexed { i,a -> a.copy(acting=true,
            activity=if (i%2==0) Activity.TALK else Activity.STUDY,skill=Skill(mastery=.75)) })
        val before=Canonical.state(world); val sound=AcousticScene.from(world)
        assertEquals(.5f,sound.social,0f); assertEquals(.75f,sound.mastery,0f)
        assertEquals(before,Canonical.state(world))
        assertEquals(sound,AcousticScene.from(Canonical.state(before)))
    }
    @Test fun historicalChangesTriggerOneMilestoneAndSocialStressIsMeasured() {
        val previous=WorldActor.genesis()
        val next=previous.copy(tick=1,rooms=previous.rooms+Room("new","Praça",2.0,10.0,1.0,1.0,2))
        val s=AcousticScene.from(next,previous)
        assertEquals(1f,s.milestone,0f)
        assertEquals(0f,AcousticScene.from(next,next).milestone,0f)
        assertEquals(next.telemetry().stress.toFloat(),s.tension,0f)
        assertTrue(s.weather in 0f..1f)
    }
    @Test fun emptyWorldIsSilentAndStable() {
        assertEquals(AcousticScene(),AcousticScene.from(WorldActor.genesis().copy(agents=emptyList())))
    }
}
