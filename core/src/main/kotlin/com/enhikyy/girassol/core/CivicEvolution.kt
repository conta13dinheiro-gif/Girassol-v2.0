package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

@Serializable
data class CivicEliteCell(val xBin:Int,val yBin:Int,val fitness:Double,val descriptorX:Double,val descriptorY:Double,val genome:List<Double>,val tick:Long)

@Serializable
data class CivicEvolutionState(
    val opinions:Map<String,Double> = emptyMap(),
    val rings:Map<String,String> = emptyMap(),
    val triadBalance:Double = 1.0,
    val psi:Double = 0.0,
    val commodityDeltas:Map<String,Long> = emptyMap(),
    val ledgerBalanced:Boolean = true,
    val pheromones:List<Double> = List(144){0.0},
    val archive:List<CivicEliteCell> = emptyList(),
    val auroraMeanX:Double = 0.0,
    val auroraMeanY:Double = 0.0,
    val auroraCovXX:Double = 1.0,
    val auroraCovXY:Double = 0.0,
    val auroraCovYY:Double = 1.0,
    val auroraAxisX:Double = 1.0,
    val auroraAxisY:Double = 0.0,
    val learning:Map<String,SkillLearningTrace> = emptyMap(),
    val learningTheta2pl:Map<String,Double> = emptyMap()
) {
    fun valid(tick:Long):Boolean =
        triadBalance.isFinite() && triadBalance in 0.0..1.0 &&
        psi.isFinite() && psi in -1.0..1.0 &&
        ledgerBalanced && commodityDeltas.values.all { it==0L } &&
        pheromones.size==144 && pheromones.all { it.isFinite() && it in 0.0..1.0 } &&
        archive.size<=64 && archive.map { it.xBin to it.yBin }.distinct().size==archive.size &&
        archive.all { it.xBin in 0..7 && it.yBin in 0..7 && it.fitness.isFinite() && it.descriptorX.isFinite() && it.descriptorY.isFinite() && it.genome.size==2 && it.genome.all(Double::isFinite) && it.tick in 0..tick } &&
        listOf(auroraMeanX,auroraMeanY,auroraCovXX,auroraCovXY,auroraCovYY,auroraAxisX,auroraAxisY).all(Double::isFinite) &&
        opinions.values.all { it.isFinite() && it in 0.0..1.0 } &&
        learningTheta2pl.values.all { it.isFinite() && it in -4.0..4.0 } &&
        learning.values.all { it.valid(tick) }
}

/** Bounded deterministic civic/evolutionary state; not a calibrated social-science predictor. */
object CivicEvolutionEngine {
    fun advance(previous:CivicEvolutionState, before:WorldState, after:WorldState, actions:List<ActionRecord>):CivicEvolutionState {
        val ids=after.agents.map { it.id }.sorted()
        val opinions=ids.associateWith { id ->
            val citizen=after.agents.first { it.id==id }
            (previous.opinions[id] ?: citizen.genome.sociability.express()).coerceIn(0.0,1.0)
        }.toMutableMap()
        // Friedkin-Johnsen anchoring, then bounded Deffuant pairwise compromise.
        ids.forEach { id ->
            val citizen=after.agents.first { it.id==id }
            opinions[id]=(.82*opinions.getValue(id)+.18*citizen.genome.sociability.express()).coerceIn(0.0,1.0)
        }
        after.ties.sortedWith(compareBy<SocialTie>{it.from}.thenBy{it.to}).forEach { tie ->
            val a=opinions[tie.from] ?: return@forEach
            val b=opinions[tie.to] ?: return@forEach
            if(abs(a-b)<.35) {
                val mu=.12*tie.affinity.coerceIn(0.0,1.0)
                opinions[tie.from]=(a+mu*(b-a)).coerceIn(0.0,1.0)
                opinions[tie.to]=(b+mu*(a-b)).coerceIn(0.0,1.0)
            }
        }
        // Dunbar-style bounded rings are labels, not hard relationship limits.
        val rings=buildMap {
            ids.forEach { id ->
                val peers=after.ties.filter { it.from==id }.sortedWith(compareByDescending<SocialTie>{it.affinity}.thenBy{it.to})
                peers.forEachIndexed { index,tie -> put("$id/${tie.to}",when(index) { in 0..4->"CLOSE_5"; in 5..19->"SYMPATHY_15"; in 20..69->"AFFINITY_50"; else->"NETWORK_150" }) }
            }
        }
        // Heider triad balance: sign product for every available undirected triad.
        var triads=0; var balanced=0
        for(i in ids.indices) for(j in i+1 until ids.size) for(k in j+1 until ids.size) {
            fun edge(a:String,b:String)=after.ties.firstOrNull { it.from==a && it.to==b }?.affinity
                ?: after.ties.firstOrNull { it.from==b && it.to==a }?.affinity
            val ab=edge(ids[i],ids[j]); val bc=edge(ids[j],ids[k]); val ca=edge(ids[k],ids[i])
            if(ab!=null && bc!=null && ca!=null) {
                triads++
                val product=(if(ab>=.5) 1 else -1)*(if(bc>=.5) 1 else -1)*(if(ca>=.5) 1 else -1)
                if(product>0) balanced++
            }
        }
        val triadBalance=if(triads==0) 1.0 else balanced.toDouble()/triads
        // Double-entry invariant by commodity.
        val commodities=(before.balances.keys+after.balances.keys).map { it.substringAfter('/') }.toSet()
        val deltas=commodities.associateWith { item ->
            after.balances.filterKeys { it.substringAfter('/')==item }.values.sumOf { it.toLong() } -
                before.balances.filterKeys { it.substringAfter('/')==item }.values.sumOf { it.toLong() }
        }
        val ledgerBalanced=deltas.values.all { it==0L }
        // O(1) pheromone deposit per citizen on a bounded 12x12 grid.
        val pheromones=previous.pheromones.map { (it*.97).coerceIn(0.0,1.0) }.toMutableList()
        after.agents.forEach { citizen ->
            val cell=citizen.x.toInt().coerceIn(0,11)+12*citizen.z.toInt().coerceIn(0,11)
            pheromones[cell]=(pheromones[cell]+.025).coerceAtMost(1.0)
        }
        val telemetry=after.telemetry()
        val psi=(.55*telemetry.gini+.35*telemetry.stress-.45*telemetry.socialCohesion).coerceIn(-1.0,1.0)
        // Existing BKT/DSR trace plus a separate bounded online 2PL ability estimate.
        val learning=previous.learning.toMutableMap()
        val theta=previous.learningTheta2pl.toMutableMap()
        actions.filter { it.outcome=="OK" && it.activity in setOf(Activity.STUDY,Activity.WRITE) }.forEach { record ->
            val old=learning[record.agentId] ?: SkillLearningTrace(record.activity)
            learning[record.agentId]=old.observe(true,after.tick)
            val current=(theta[record.agentId] ?: 0.0).coerceIn(-4.0,4.0)
            val b=(old.difficulty-5.5)/2.25
            val p=1.0/(1.0+exp(-(1.2*(current-b)).coerceIn(-12.0,12.0)))
            theta[record.agentId]=(current+.08*(1.0-p)).coerceIn(-4.0,4.0)
        }
        // MAP-Elites 8x8 archive; two descriptors and two genome summaries.
        val entropy=SemanticCollapseGuard.normalizedPopulationEntropy(after.actionCounts)
        val x=telemetry.socialCohesion.coerceIn(0.0,1.0)
        val y=entropy.coerceIn(0.0,1.0)
        val xb=(x*8).toInt().coerceIn(0,7); val yb=(y*8).toInt().coerceIn(0,7)
        val fitness=(.45*x+.25*y+.30*(1.0-telemetry.stress)).coerceIn(0.0,1.0)
        val genome=listOf(after.agents.map { it.genome.curiosity.express() }.average(),after.agents.map { it.genome.sociability.express() }.average())
        val archive=previous.archive.toMutableList()
        val slot=archive.indexOfFirst { it.xBin==xb && it.yBin==yb }
        val elite=CivicEliteCell(xb,yb,fitness,x,y,genome,after.tick)
        if(slot<0) archive+=elite else if(fitness>archive[slot].fitness) archive[slot]=elite
        val boundedArchive=archive.sortedWith(compareBy<CivicEliteCell>{it.xBin}.thenBy{it.yBin}).take(64)
        // Incremental covariance and a 2D PCA direction (AURORA-like projection).
        val n=after.tick.coerceAtMost(1_000_000L).toDouble().coerceAtLeast(1.0)
        val dx=x-previous.auroraMeanX; val dy=y-previous.auroraMeanY
        val mx=previous.auroraMeanX+dx/n; val my=previous.auroraMeanY+dy/n
        val cxx=(previous.auroraCovXX+dx*(x-mx)).coerceAtLeast(1e-9)
        val cxy=previous.auroraCovXY+dx*(y-my)
        val cyy=(previous.auroraCovYY+dy*(y-my)).coerceAtLeast(1e-9)
        var ax=previous.auroraAxisX; var ay=previous.auroraAxisY
        repeat(8) { val nx=cxx*ax+cxy*ay; val ny=cxy*ax+cyy*ay; val norm=sqrt(nx*nx+ny*ny).coerceAtLeast(1e-12); ax=nx/norm; ay=ny/norm }
        return CivicEvolutionState(opinions,rings,triadBalance,psi,deltas,ledgerBalanced,pheromones,boundedArchive,mx,my,cxx,cxy,cyy,ax,ay,learning,theta)
    }
}
