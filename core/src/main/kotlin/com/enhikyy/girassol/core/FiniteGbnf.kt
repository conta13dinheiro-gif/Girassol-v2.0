package com.enhikyy.girassol.core

/** Bounded compiler for the acyclic, finite GBNF subset used by VPO.
 * Literals, named productions, concatenation and alternatives are supported.
 * Repetition, recursion, escapes and unknown symbols are rejected, never executed.
 */
internal object FiniteGbnf {
    fun compile(grammar: String): Set<String> {
        require(grammar.length<=4096)
        val rules=linkedMapOf<String,String>()
        for(line in grammar.lineSequence().filter { it.isNotBlank() }) {
            val parts=line.split("::=",limit=2)
            require(parts.size==2)
            val name=parts[0].trim()
            require(name.matches(Regex("[a-z][a-z0-9-]*")) && name !in rules)
            rules[name]=parts[1].trim()
        }
        require(rules.size in 1..16 && "root" in rules)
        val token=Regex("\"[^\"\\\\]*\"|[a-z][a-z0-9-]*|\\|")
        val cache=mutableMapOf<String,Set<String>>()
        fun expand(name: String,path: Set<String>): Set<String> {
            require(name !in path && name in rules)
            cache[name]?.let { return it }
            val body=rules.getValue(name)
            val parts=mutableListOf<List<String>>()
            var values=listOf(""); var offset=0
            for(match in token.findAll(body)) {
                require(body.substring(offset,match.range.first).isBlank())
                offset=match.range.last+1
                if(match.value=="|") { parts+=values; values=listOf(""); continue }
                val suffix=if(match.value.startsWith('"')) setOf(match.value.substring(1,match.value.length-1)) else expand(match.value,path+name)
                require(values.size*suffix.size<=128)
                values=values.flatMap { a -> suffix.map { b -> (a+b).also { require(it.length<=140) } } }
            }
            require(body.substring(offset).isBlank() && offset>0)
            parts+=values
            val result=parts.flatten().toSet()
            require(result.size in 1..128)
            cache[name]=result
            return result
        }
        return expand("root",emptySet())
    }
}
