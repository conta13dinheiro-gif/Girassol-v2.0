package com.enhikyy.girassol.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PedagogyTest {
    private fun invalid(body: ()->Unit) { try { body();fail("Invalid input accepted") } catch(_: IllegalArgumentException) { } }
    @Test fun fsrsMatchesPinnedPrimaryImplementationFourGradeGoldens() {
        // Independent golden output extracted from ts-fsrs v3.5.7 math methods, not Girassol.
        val initD=doubleArrayOf(7.5455,6.3449,5.1443,3.9437)
        val initS=doubleArrayOf(.5701,1.4436,4.1386,10.9355)
        val nextD=doubleArrayOf(6.80724052,5.97577026,5.1443,4.31282974)
        val nextS=doubleArrayOf(1.51147916,6.97064574,17.07031569,40.65388559)
        val good=Fsrs45.review(null,ReviewGrade.GOOD,1)
        for((i,grade) in ReviewGrade.entries.withIndex()) {
            val first=Fsrs45.review(null,grade,1)
            assertEquals(initD[i],first.difficulty,1e-10);assertEquals(initS[i],first.stability,1e-10)
            val next=Fsrs45.review(good,grade,6)
            assertEquals(nextD[i],next.difficulty,1e-7);assertEquals(nextS[i],next.stability,1e-5)
            assertTrue(next.valid(6));assertEquals(if(i==0) 1 else 0,next.lapses)
        }
    }
    @Test fun fsrsCurveIntervalBoundsAndLongStreamingAreFinite() {
        assertEquals(.9,Fsrs45.recall(7.0,7.0),1e-12)
        assertEquals(7.0,Fsrs45.interval(7.0),1e-12)
        assertTrue(Fsrs45.interval(7.0,.95)<Fsrs45.interval(7.0,.9))
        var state: FsrsReview?=null
        repeat(10000) { i -> state=Fsrs45.review(state,ReviewGrade.entries[i%4],i.toLong());assertTrue(state!!.valid(i.toLong())) }
        invalid { Fsrs45.review(state,ReviewGrade.GOOD,9999) }
        invalid { Fsrs45.recall(-1.0,1.0) };invalid { Fsrs45.interval(1.0,Double.NaN) }
        assertFalse(state!!.copy(modelVersion=46).valid(9999))
    }
    @Test fun irtTwoAndThreeParameterModelsHaveExactDerivativesAndInformation() {
        for(model in IrtModel.entries) {
            val item=IrtItem(model,1.4,.3,if(model==IrtModel.TWO_PL) 0.0 else .15)
            val theta=.7;val h=1e-6;val exact=item.derivatives(theta)
            val numerical=doubleArrayOf(
                (item.probability(theta+h)-item.probability(theta-h))/(2*h),
                (item.copy(discrimination=1.4+h).probability(theta)-item.copy(discrimination=1.4-h).probability(theta))/(2*h),
                (item.copy(difficulty=.3+h).probability(theta)-item.copy(difficulty=.3-h).probability(theta))/(2*h),
                if(model==IrtModel.TWO_PL) 0.0 else (item.copy(guessing=.15+h).probability(theta)-item.copy(guessing=.15-h).probability(theta))/(2*h))
            for(i in exact.indices) assertEquals(numerical[i],exact[i],1e-8)
            val p=item.probability(theta)
            assertEquals(exact[0].pow(2)/(p*(1-p)),item.information(theta),1e-12)
            assertTrue(item.probability(-4.0)<item.probability(4.0))
        }
    }
    @Test fun irtMapLearnsActualResponsesAndRegularizesWithinBounds() {
        for(action in listOf(Activity.STUDY,Activity.BUILD)) {
            val prior=IrtItem.prior(action)
            val (up,success)=prior.observe(0.0,true,prior);val (down,failure)=prior.observe(0.0,false,prior)
            assertTrue(up>0);assertTrue(down<0)
            assertTrue(success.probability(up)>prior.probability(0.0))
            assertTrue(failure.probability(down)<prior.probability(0.0))
            var theta=0.0;var item=prior
            repeat(10000) { i -> val next=item.observe(theta,i%3!=0,prior);theta=next.first;item=next.second;assertTrue(item.valid());assertTrue(theta in -4.0..4.0) }
            assertEquals(10000,item.observations)
        }
        invalid { IrtItem(IrtModel.TWO_PL,guessing=.1).probability(0.0) }
    }
    @Test fun geneticBktAndFsrsMigrationPreservePriorAndDoNotInventExecutions() {
        val initial=SkillLearningTrace(Activity.STUDY)
        val slow=initial.observeFsrs(ReviewGrade.GOOD,1,0.0,true)
        val fast=initial.observeFsrs(ReviewGrade.GOOD,1,1.0,true)
        // P(L|correct)=.184/(.184+.12), then .10 or .28 learning transition.
        assertEquals(.184/.304+(1-.184/.304)*.10,slow.knowledgeProbability,1e-12)
        assertEquals(.184/.304+(1-.184/.304)*.28,fast.knowledgeProbability,1e-12)
        assertEquals(1,fast.verifiedExecutions);assertTrue(fast.valid(1))
        val old=initial.observe(true,1)
        val migrated=old.observeFsrs(ReviewGrade.GOOD,2,.5,false)
        assertEquals(2,migrated.reviews);assertEquals(0,migrated.verifiedExecutions);assertEquals(1,migrated.item!!.observations)
        assertTrue(migrated.valid(2));assertTrue(migrated.practiceBonus(100)>migrated.practiceBonus(2))
        val json=Json.encodeToString(old)
        assertFalse(json.contains("fsrs"));assertFalse(json.contains("item"));assertFalse(json.contains("verifiedExecutions"))
    }
    @Test fun sharingRequiresBothLocalExecutionsAndMasteryWithoutTransferringCounts() {
        val world=WorldActor.genesis().copy(tick=4);val author=world.agents[0];val student=world.agents[1]
        var trace=SkillLearningTrace(Activity.STUDY)
        repeat(3) { i -> trace=trace.observeFsrs(ReviewGrade.GOOD,(i+1).toLong(),.5,true) }
        val proof=(1L..3L).map { SkillProof(it,"a".repeat(64),"b".repeat(64),Activity.STUDY.room) }
        val skill=VoyagerSkill(author.id,Activity.STUDY,SkillPreconditions("LOGIC"),VoyagerSkillLibrary.recipe(Activity.STUDY),3,trace.knowledgeProbability,proof,3)
        assertTrue(skill.valid(4))
        val teacher=MemoryBank(learning=listOf(trace),skills=listOf(skill));val apprentice=MemoryBank()
        val taught=VoyagerSkillLibrary.teach(world,student,apprentice,author,teacher)
        assertEquals(author.id,taught.skills.single().teacher)
        assertEquals(0,taught.learningFor(Activity.STUDY).verifiedExecutions)
        assertFalse(taught.skills.single().admits(world,student,taught))
        val knowledgeable=taught.copy(learning=listOf(trace.copy(verifiedExecutions=0)))
        assertTrue(knowledgeable.skills.single().admits(world,student,knowledgeable))
        val cannotForward=VoyagerSkillLibrary.teach(world,world.agents[2],apprentice,student,knowledgeable)
        assertTrue(cannotForward.skills.isEmpty())
        val unqualified=teacher.copy(learning=listOf(trace.copy(knowledgeProbability=.74)))
        assertTrue(VoyagerSkillLibrary.teach(world,student,apprentice,author,unqualified).skills.isEmpty())
        assertFalse(skill.copy(operators=skill.operators.reversed()).valid(4))
    }
    @Test fun interpretedToolProgramRenewsExpiredLeaseAndSettlesRealTimber() {
        val base=WorldActor.genesis().copy(tick=4)
        val room=base.rooms.first { it.id==Activity.BUILD.room };val citizen=base.agents.first().copy(x=room.x,z=room.z,needs=Needs(1.0,1.0,1.0,1.0,1.0))
        var trace=SkillLearningTrace(Activity.BUILD)
        repeat(3) { trace=trace.observeFsrs(ReviewGrade.GOOD,it.toLong()+1,.5,true) }
        val skill=VoyagerSkill(citizen.id,Activity.BUILD,SkillPreconditions("ENGINEERING"),VoyagerSkillLibrary.recipe(Activity.BUILD),3,trace.knowledgeProbability,
            (1L..3L).map { SkillProof(it,"a".repeat(64),"b".repeat(64),room.id) },3)
        val memory=MemoryBank(learning=listOf(trace),skills=listOf(skill));val minds=mapOf(citizen.id to MindState.initial(base,citizen).copy(memory=memory))
        val world=base.copy(agents=base.agents.map { if(it.id==citizen.id) citizen else it },minds=minds)
        val balances=world.balances.toMutableMap();val transfers=mutableListOf<StockTransfer>()
        val writer: (String,String,String,Int)->Boolean={ f,t,r,n -> WorldActor.transfer(balances,f,t,r,n).also { if(it) transfers+=StockTransfer(f,t,r,n) } }
        val first=DoubleEntryEconomyAndStigmergy(world,minds,writer)
        first.prepare(citizen,Activity.BUILD,room)
        val moving=first.snapshot();assertEquals(1,moving.jobs.single().programCounter)
        // Lease expires at35; at tick34 the saved state is valid and the next writer must reserve anew.
        val prior=world.copy(tick=34,economy=moving)
        assertTrue(moving.valid(prior))
        val continued=DoubleEntryEconomyAndStigmergy(prior,minds,writer)
        continued.prepare(citizen,Activity.BUILD,room)
        assertEquals(65L,continued.snapshot().leases.single().expires)
        assertTrue(continued.reserve(citizen,room))
        assertTrue(continued.beginWork(citizen,Activity.BUILD,balances))
        assertEquals(1,balances.getValue("tools/timber"))
        assertTrue(WorldActor.transfer(balances,"market","structures","timber",4))
        continued.finish(citizen,Activity.BUILD,true)
        val done=continued.snapshot()
        assertEquals(5,done.jobs.single().programCounter);assertEquals("DONE",done.jobs.single().phase)
        assertEquals(31,done.tools.single().uses);assertEquals(35L,done.jobs.single().receiptTick)
        assertTrue(done.pheromones.any { it>0 });assertEquals(1,transfers.count { it.to=="tools" && it.resource=="timber" })
        assertEquals(world.initialTotals.getValue("timber"),balances.filterKeys { it.endsWith("/timber") }.values.sum())
    }

}
