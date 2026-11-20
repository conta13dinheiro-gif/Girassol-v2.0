package com.enhikyy.girassol.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class SocietyTest {
    private val initial=WorldActor.genesis()
    private fun state(world: WorldState,rules: List<AdicoRule> = SocietyAdicoCliodynamicsEngine.foundingRules(),usage: List<NormUsage> = emptyList())=SocietyState(rules,
        world.agents.associate { it.id to SocietyAdicoCliodynamicsEngine.initialOpinion(world,it) },usage,emptyList(),emptyList(),
        SocietyAdicoCliodynamicsEngine.measure(world))
    private inline fun invalid(block: ()->Unit) { try { block();fail("Invalid input accepted") } catch(_: IllegalArgumentException) { } }
    @Test fun dunbarRingsAreNestedAndHaveStrict150RelationshipStorage() {
        val outgoing=(0 until 150).map { SocialTie("owner","peer%03d".format(it),.75) }
        assertEquals(DunbarRing.INTIMATE,DunbarNetwork.ring(outgoing,"peer004"))
        assertEquals(DunbarRing.SYMPATHY,DunbarNetwork.ring(outgoing,"peer005"))
        assertEquals(DunbarRing.FUNCTIONAL,DunbarNetwork.ring(outgoing,"peer015"))
        assertEquals(DunbarRing.COMMUNITY,DunbarNetwork.ring(outgoing,"peer050"))
        assertEquals(DunbarRing.COMMUNITY,DunbarNetwork.ring(outgoing,"peer149"))
        assertNull(DunbarNetwork.ring(outgoing,"peer150"))
        invalid { DunbarNetwork.ordered(outgoing+SocialTie("owner","extra")) }
    }
    @Test fun betaTracksObservedSuccessFailureAndRetainsNonzeroPriorIndefinitely() {
        val positive=DomainBeta().observe(true)
        val negative=DomainBeta().observe(false)
        assertEquals(.625,positive.mean,1e-12)
        assertEquals(1.5/5.2,negative.mean,1e-12)
        var beta=DomainBeta()
        repeat(100000) { beta=beta.observe(true);assertTrue(beta.valid());assertTrue(beta.beta>=.5) }
        assertTrue(beta.alpha+beta.beta<=1024.5)
        val before=beta.mean;repeat(100) { beta=beta.observe(false) }
        assertTrue(beta.mean<before)
    }
    @Test fun domainPosteriorActuallyControlsAssimilationRatherThanGeneralPopularity() {
        val tie=SocialTie("agent0","agent1",alpha=99.0,beta=1.0,domains=mapOf("LOGIC" to DomainBeta(9.0,1.0),"LANGUAGE" to DomainBeta(1.0,9.0)))
        val world=initial.copy(ties=initial.ties.filterNot { it.from==tie.from && it.to==tie.to }+tie)
        assertEquals(.54,ProvenanceImmunity.trust(world,"agent0","agent1","LOGIC"),1e-12)
        assertEquals(.06,ProvenanceImmunity.trust(world,"agent0","agent1","LANGUAGE"),1e-12)
        val evidence=CognitiveEvidence(Canonical.hash("claim"),Canonical.hash("root"),Canonical.hash("dataset"),"LOGIC","agent1",95,0,WorldActor.GENESIS_HASH)
        assertNotNull(ProvenanceImmunity.admit(world,"agent0",MemoryBank(),evidence))
        assertNull(ProvenanceImmunity.admit(world,"agent0",MemoryBank(),evidence.copy(domain="LANGUAGE")))
    }
    @Test fun hybridMatchesIndependentBoundedConfidenceAndIntrinsicAnchoringCalculation() {
        val own=SocialOpinion(.2,-.1,.65)
        assertEquals(.121,SocietyAdicoCliodynamicsEngine.hybrid(own,.4,.8),1e-12)
        assertEquals(.095,SocietyAdicoCliodynamicsEngine.hybrid(own,.9,.8),1e-12)
        assertEquals(0.0,SocietyAdicoCliodynamicsEngine.hybrid(SocialOpinion(0.0,0.0,1.0),.45,1.0),0.0)
        invalid { SocietyAdicoCliodynamicsEngine.hybrid(own,Double.NaN,.5) }
    }
    @Test fun heiderReducesImbalancedTriadEnergyWhilePreservingAffinityContract() {
        val updated=SocietyAdicoCliodynamicsEngine.balancedAffinity(.9,.9,.1)
        assertEquals(.115,updated,1e-12)
        assertTrue(abs((2*.9-1)*(2*.9-1)*(2*updated-1))<abs((2*.9-1)*(2*.9-1)*(2*.1-1)))
        assertEquals(.9,SocietyAdicoCliodynamicsEngine.balancedAffinity(.9,.9,.9),0.0)
        invalid { SocietyAdicoCliodynamicsEngine.balancedAffinity(-.1,.9,.1) }
    }
    @Test fun adicoParsesRealGrammarAndRejectsUnsupportedOrUnsafeActions() {
        for(rule in SocietyAdicoCliodynamicsEngine.foundingRules()) assertEquals(rule,AdicoRule.parse(rule.statement()))
        val real=AdicoRule.parse("LINEAGE:${initial.agents[0].lineage} OBRIGADO STUDY WHEN ALWAYS 0 ELSE FINE 0 QUOTA 0 WINDOW 240")
        assertTrue(real.applies(initial,initial.agents[0]))
        assertFalse(real.applies(initial,initial.agents.first { it.lineage!=initial.agents[0].lineage }))
        for(text in listOf("ALL EXEC BUILD WHEN ALWAYS 0 ELSE FINE 1 QUOTA 0 WINDOW 240",
            "ALL PROIBIDO WATER WHEN ALWAYS 0 ELSE FINE 1 QUOTA 0 WINDOW 240",
            "ALL PROIBIDO BUILD WHEN TIMBER_LT NaN ELSE FINE 1 QUOTA 0 WINDOW 240",
            "ALL PERMITIDO WRITE WHEN ALWAYS 0 ELSE FINE 1 QUOTA 0 WINDOW 240",
            "ALL PROIBIDO BUILD WHEN ALWAYS 0 ELSE FINE 999 QUOTA 0 WINDOW 240",
            "ALL PROIBIDO BUILD WHEN ALWAYS 0 ELSE FINE 1 QUOTA 0 WINDOW 240\n")) invalid { AdicoRule.parse(text) }
    }
    @Test fun normsEnforceProhibitionObligationAndExactQuotaWindowBoundary() {
        val citizen=initial.agents[0].copy(needs=Needs(1.0,1.0,1.0,1.0,.1))
        val balances=initial.balances.toMutableMap();assertTrue(WorldActor.transfer(balances,"market","environment","timber",balances.getValue("market/timber")-7))
        val scarce=initial.copy(balances=balances)
        val block=SocietyAdicoCliodynamicsEngine.admit(scarce,citizen,Activity.BUILD)
        assertEquals(Activity.STUDY,block.action);assertEquals(Deontic.PROIBIDO,block.violation!!.deontic)
        val thirsty=citizen.copy(needs=citizen.needs.copy(hydration=.25))
        val mandatory=SocietyAdicoCliodynamicsEngine.admit(initial,thirsty,Activity.BUILD)
        assertEquals(Activity.WATER,mandatory.action);assertEquals(Deontic.OBRIGADO,mandatory.violation!!.deontic)
        val permit=SocietyAdicoCliodynamicsEngine.foundingRules().first { it.deontic==Deontic.PERMITIDO }
        var world=initial.copy(tick=239)
        world=world.copy(society=state(world,usage=listOf(NormUsage(citizen.id,permit.id,0,8))))
        assertEquals(Activity.STUDY,SocietyAdicoCliodynamicsEngine.admit(world,citizen,Activity.WRITE).action)
        assertEquals(Activity.WRITE,SocietyAdicoCliodynamicsEngine.admit(world.copy(tick=240),citizen,Activity.WRITE).action)
        val critical=citizen.copy(needs=citizen.needs.copy(hydration=.01))
        assertNull(SocietyAdicoCliodynamicsEngine.admit(world,critical,Activity.WRITE).violation)
        assertEquals(Activity.WATER,SocietyAdicoCliodynamicsEngine.admit(world,critical,Activity.WRITE).action)
    }
    @Test fun turchinUsesObservableWealthAndTreasuryWithFiniteZeroWealthLimit() {
        val pressure=SocietyAdicoCliodynamicsEngine.pressure(4,4.0,listOf(0.0,0.0,0.0,40.0),4,1,8.0)
        assertEquals(.75,pressure.gini,1e-12);assertEquals(22.0,pressure.psi,1e-12)
        assertTrue(pressure.valid())
        val empty=SocietyAdicoCliodynamicsEngine.pressure(4,4.0,List(4) { 0.0 },0,1,0.0)
        assertEquals(.05,empty.fiscal,0.0);assertEquals(0.0,empty.psi,0.0)
        invalid { SocietyAdicoCliodynamicsEngine.pressure(0,4.0,emptyList(),0,1,0.0) }
        invalid { SocietyAdicoCliodynamicsEngine.pressure(4,4.0,List(4) { Double.MAX_VALUE },0,1,0.0) }
    }
    @Test fun actualWitnessedWorkUpdatesDomainTrustWhileUnobservedWorkDoesNot() {
        val source=initial.agents[1]
        val world=initial.copy(agents=initial.agents.mapIndexed { i,a -> a.copy(x=if(i<2) 2.0 else 40.0+i,z=2.0) })
        val next=world.copy(tick=1)
        val record=listOf(ActionRecord(source.id,Activity.BUILD,"OK"))
        val result=SocietyAdicoCliodynamicsEngine(world).settle(next,next,record,emptyList(),null)
        val observed=result.ties.first { it.from=="agent0" && it.to==source.id }
        assertEquals(.625,observed.domains.getValue("ENGINEERING").mean,1e-12)
        val remote=result.ties.first { it.from=="agent2" && it.to==source.id }
        assertTrue(remote.domains.isEmpty())
        assertTrue(result.state.valid(next.copy(ties=result.ties,society=result.state)))
    }
    @Test fun firstContactUsesOneImmutableOpinionSnapshotForBothDirections() {
        val world=initial.copy(agents=initial.agents.mapIndexed { i,a ->
            if(i<2) a.copy(genome=a.genome.copy(sociability=Locus(if(i==0) .45 else .55,if(i==0) .45 else .55,.5,.5))) else a
        })
        val next=world.copy(tick=1)
        val contacts=listOf(SocialContact("agent0","agent1","GOVERNANCE",1),SocialContact("agent1","agent0","GOVERNANCE",1))
        val settled=SocietyAdicoCliodynamicsEngine(world).settle(next,next,emptyList(),contacts,null)
        assertEquals(-.08375,settled.state.opinions.getValue("agent0").value,1e-12)
        assertEquals(.08375,settled.state.opinions.getValue("agent1").value,1e-12)
        val reversed=SocietyAdicoCliodynamicsEngine(world).settle(next,next,emptyList(),contacts.reversed(),null)
        assertEquals(settled.state.opinions,reversed.state.opinions)
    }
    @Test fun historicalEncodingOmitsEveryNewSocialDefault() {
        val encoded=Canonical.state(initial)
        for(key in listOf("society","domains","familiarity","institutions")) assertFalse(encoded.contains("\"$key\""))
        assertEquals(initial,Canonical.state(encoded))
    }
}
