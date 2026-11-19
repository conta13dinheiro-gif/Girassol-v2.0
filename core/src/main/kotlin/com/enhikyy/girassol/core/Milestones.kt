package com.enhikyy.girassol.core

/** Causes are taken from committed transitions, never invented by a text generator. */
data class MilestoneCause(val kind: String,val title: String,val cause: String,val agents: List<String>)
object Milestones {
    fun detect(previous: WorldState?,transition: Transition): List<MilestoneCause> {
        val w=transition.state
        val out=mutableListOf<MilestoneCause>()
        if(previous==null) out+=MilestoneCause("origin","Primeiros caminhos","Primeiro estado registrado nesta linha do tempo.",w.agents.map { it.id })
        if(previous!=null) {
            if(w.evolution!=null && w.evolution!=previous.evolution) out+=MilestoneCause("evolution","Uma política comprovada","A política superou a referência nas janelas de treino e validação de ${w.evolution.horizon} ciclos, sem regressão nas quatro métricas.",w.agents.map { it.id })
            val built=w.rooms.filter { r->previous.rooms.none { it.id==r.id } }
            if(built.isNotEmpty()) out+=MilestoneCause("architecture","A cidade se expande",built.joinToString { it.name }+" construído com recursos do mercado.",transition.event.actions.filter { it.activity==Activity.BUILD && it.outcome=="OK" }.map { it.agentId })
            val teachers=w.agents.filter { a->a.skill.shareable && previous.agents.firstOrNull { it.id==a.id }?.skill?.shareable==false }
            if(teachers.isNotEmpty()) out+=MilestoneCause("knowledge","Conhecimento comprovado","Três práticas bem-sucedidas habilitam a transmissão de uma habilidade.",teachers.map { it.id })
            if(previous.artifacts.isEmpty() && w.artifacts.isNotEmpty()) out+=MilestoneCause("culture","A primeira obra","Uma experiência foi registrada pelos próprios habitantes.",transition.event.actions.filter { it.activity==Activity.WRITE && it.outcome=="OK" }.map { it.agentId })
            val crisis=w.agents.filter { a->minOf(a.needs.energy,a.needs.hydration,a.needs.glucose)<.15 && previous.agents.firstOrNull { it.id==a.id }?.let { minOf(it.needs.energy,it.needs.hydration,it.needs.glucose)>=.15 }==true }
            if(crisis.isNotEmpty()) out+=MilestoneCause("crisis","Uma necessidade urgente","A disponibilidade de energia, água ou alimento caiu abaixo de 15%.",crisis.map { it.id })
            if(previous.minds.isEmpty() && w.minds.isNotEmpty()) out+=MilestoneCause("cognition","Mentes em transformação","As primeiras redes LTC foram integradas ao diário da biosfera.",w.minds.keys.toList())
        }
        if(w.tick%1440==0L) out+=MilestoneCause("era","Dia ${w.tick/1440} concluído","Estado integral da biosfera ao completar um dia simulado.",emptyList())
        return out
    }
}
