<!-- coderabbit-ai-generated:v1 -->

# Treinamento local do readout LTC — LoRA+

## Escopo e consumidores

O adaptador nativo opera no readout real 8×8 da LTC, com posto 4 e alpha 16. `NativeMind` fornece os pesos 184..247 e a ativação de oito neurônios. A saída adaptada entra no quinto módulo do PIANO e no `DecisionTrace.nativeActionValues`. A utilidade entregue ao decisor é limitada a [-64,64], preservando o intervalo histórico da projeção normalizada [-8,8]. O otimizador modifica exclusivamente A/B; a plasticidade Hebbiana, a simbiogênese e o SWR conservam a propriedade das matrizes base. A inferência ocorre antes do SWR, preservando a propriedade dos escores do ciclo acordado.

O alvo supervisionado é a recompensa de uma ação realmente executada no tick anterior. O treino usa o readout e a ativação do estado canônico anterior; isso inclui eventuais alterações base consolidadas naquele estado. As outras sete saídas recebem o próprio valor previsto com stop-gradient. Não se atribui ao exemplo uma execução Voyager nem domínio de habilidade. Cada agente treina no máximo uma vez por tick com recibo canônico; `MODERATE`/`SEVERE_OR_CRITICAL` suspendem o treino. Um recém-nascido inicializa seu próprio adaptador, preservando as regras v8 de herança dos alelos.

Este escopo treina a cabeça decisória LTC. As matrizes internas de modelos GGUF/llama.cpp continuam fora deste otimizador; não há evidência de treino de um transformador neste recurso.

## Matemática e limites

- y = W0 x + 4 B(Ax); B inicialmente zero, identidade exata no passo zero.
- Inicialização A via SplitMix64 e uniforme de variância 1/8, especificada independentemente da implementação da biblioteca padrão. Esta escolha substitui a normal da referência sem alterar as fórmulas do adaptador.
- MSE médio nas oito saídas, derivadas de A e B calculadas com B anterior à atualização.
- LoRA+: eta_A=0,001; eta_B=0,004. AdamW beta1=0,9, beta2=0,999, epsilon=1e-8 e decay=0,01, com correção de viés.
- EWC: g_reg=g+15 F(theta-anchor); Fisher diagonal empírica F=0,95 F+0,05 g² usando gradiente dos dados. A âncora é consolidada após cada 32 atualizações reais, mantendo Fisher online.
- 64 parâmetros; cinco arrays fixos para pesos, m, v, Fisher e âncora. Estado C++ 2.576 bytes; formato binário little-endian LRA1 de 2.592 bytes. Forward, backward, decodificação e serialização usam arrays fixos e nenhuma alocação de heap C++.
- Pesos/âncoras limitados a módulo 8, momentos a 1e12, v/F a [0,1e24], passos até 1e12. Entradas não finitas, dimensões incorretas ou um passo que extrapole os limites são rejeitados integralmente. A ponte exige buffers diretos com capacidades exatas, regiões disjuntas e saídas graváveis. Nenhuma saída é publicada parcialmente.
- Workspace JNI/Kotlin reutilizado por thread: 2.592+576+64 bytes diretos. 150 estados binários somam 388.800 bytes; representação hexadecimal Kotlin, eventos e os demais motores têm custos adicionais. Isso é um limite desta estrutura, sem medição do pico total de RAM no aparelho.

## Persistência e compatibilidade

Schema 7 adiciona `lora_adapter_checkpoints` com índices, FK para evento de escrita/origem, limites de BLOB e metadados. As oito tabelas do Schema 5 e o ledger RSI do Schema 6 são preservados. Eventos e checkpoint LoRA são gravados na mesma transação; somente adaptadores alterados produzem nova linha. A retomada restaura A/B, m/v, Fisher, âncora e contadores, permitindo continuar o próximo passo exatamente. Fork copia somente o prefixo escolhido.

O checkpoint canônico inclui o estado imutável, hash do mundo de origem e hash dos pesos base/ativação usados no treino. A auditoria verifica recibo anterior bem-sucedido, avanço de um passo, origem, FK, digest e igualdade entre BLOB e evento; detecta linhas faltantes e adulterações com digest recalculado. O campo opcional ausente preserva a codificação histórica. CURRENT_RULES_VERSION permanece 8 e idade permanece Double.

## Verificação executável

`scripts/test-native.sh` inclui diferenças finitas para os 64 gradientes, primeiro passo AdamW independente, Fisher/EWC, convergência, checkpoints, negação de malloc/calloc e ASan/UBSan. `scripts/test-lora-sqlite.py` transporta um otimizador C++ efetivamente treinado por SQLite e exige igualdade após mais 40 passos num processo novo; verifica prefixo de fork e rejeição de corrupção sem sinal fatal.

`LoraIntegration` executa JNI e o ciclo real com recibos, ligação ao PIANO, bloqueio térmico, proveniência, restauração e replay. `LoraPersistenceTest` instrumentado cobre o DAO Android, reabertura e adulteração/remoção de linhas; execução em aparelho depende de alvo Android disponível. As evidências efetivamente executadas ficam no progresso da implementação.
