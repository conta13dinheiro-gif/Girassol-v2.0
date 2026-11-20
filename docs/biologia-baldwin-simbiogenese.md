<!-- coderabbit-ai-generated:v1 -->

# Frente 9 — seleção Baldwiniana e simbiose funcional

## Contrato preservado e diferença do protótipo

O blueprint técnico (seção Baldwin/simbiogênese) propõe alterar alelos parentais. Isso contradiz a exigência explícita de preservar segregação v8. Esta implementação conserva valores e dominâncias: cada filho recebe exatamente um alelo de cada progenitor, com o mesmo PRNG `inheritance`, ID por SHA-256, população máxima de 150 e geração verificada. A idade Kotlin continua em Double e CURRENT_RULES_VERSION continua 8. A herança de pesos aprendidos preexistente foi preservada; este modelo não é uma demonstração biológica empírica.

A ativação `biologyEnabled` é entrada canônica opcional, omitida por padrão. Eventos históricos sem ela conservam sua admissão anterior. Após ativação, não é permitido omitir a entrada cognitiva nas transições seguintes.

## Seleção dependente de aprendizado

Após as pré-condições v8 (par ordenado, afinidade recíproca >= 0,90, intervalo de 1.440 ciclos e capacidade), a admissão usa `p = 0,25 + 0,75*(L1+L2)/2`. Para cada progenitor, `L = soma(P(L)*N/(N+8))/8`, contando somente domínios com pelo menos três execuções próprias verificadas e P(L)>=0,75. Competência em mais domínios aumenta a aptidão. A amostragem usa domínio PRNG separado `baldwin-selection-v1`, sem consumir o sorteio da meiose. Cada tentativa registra pais, aptidões, probabilidade, sorteio e resultado no evento/checkpoint SQLite.

## Simbiose entre módulos aprendidos

Um contato TALK concluído pode incorporar um bloco funcional do readout LTC (oito pesos de uma ação) de outra linhagem. Exige programa Voyager e prática própria do doador, P(L)>=0,75, ganho de competência >=0,05, confiança recíproca por domínio >=0,70 e admissão pelo sistema de imunidade >=0,70. O bloco recebe mistura limitada `w = (1-0,25*t)*w_receptor + 0,25*t*w_doador`, com pesos em [-8,8]. O módulo entra antes do forward C++ real. Não transfere contagens de execução nem atribui domínio ao receptor.

No máximo um módulo por agente e avaliação, oito módulos retidos por agente e intervalo mínimo de 120 ciclos por ação. O registro preserva doador, hash do genoma, hash do bloco, hashes antes/depois, evento de origem, confiança e evidência de domínio. A aplicabilidade reexecuta a seleção sobre o mundo de origem; WorldStore verifica a origem dentro da transação. A auditoria SQLite revalida os blocos e evidências contra o evento precedente. Não há nova tabela nem novo buffer JNI; usa a persistência canônica existente.

## Diversidade semântica operacional

O espaço semântico é explicitamente o dos oito conceitos de ação, sem embeddings linguísticos. Cada agente fornece até 32 memórias reais recentes. Ausência de observações é desconhecimento, não distribuição uniforme. H_sem agregado é Shannon/ln(8). Diversidade entre agentes é Jensen-Shannon generalizada/ln(8), com igual peso por perfil observado; duplicar toda a população não altera o índice. Perfis uniformes idênticos têm H=1 e divergência=0; oito perfis concentrados em conceitos distintos têm divergência=1. Dois conceitos disjuntos equiprováveis têm índice 1/3.

Abaixo de H=0,55, o PIANO recebe bônus limitado a 0,10 proporcional à escassez do conceito. Os contadores globais usam soma Long e saturam Int.MAX_VALUE no fluxo ativado. Estado de seleção limitado a 150 pares por ciclo; perfis limitados a 150 x 32 observações, retenção total de módulos a 150 x 8. Esses limites estruturais não substituem medição de RAM de todo o aplicativo no Moto G62, carga 24/7 ou ensaio de GPU/áudio no dispositivo.

## Evidência

Os resultados executados desta entrega e seus limites são registrados em `progresso-implementacao-girassol.md`. Testes Android de SQLite compilados só contam como execução quando houver alvo Android disponível.
