# Continuação do Girassol — estado verificável

<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:b82755fed364719e77b3e00af5b3682d152464e284a1083d99c837b421fde920 -->

<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:936796b576d419b4965f23e11f973d8c3be68e5e633772864eb128e26cfb9c2a -->

Objetivo solicitado: completar as 11 frentes de `knowledge-base/girassol-diretrizes.md`. **Objetivo ainda não atingido.**

**Estado mais recente: 45,5% de cobertura funcional local (5/11 frentes: 3, 5, 1, 8 e 2).** Critério: frentes com implementação integrada e verificações locais, todas com peso igual; não mede esforço, RAM ou prontidão de produção. Evidências mais recentes estão em “Entrega atual — Frente 2”, ao final. As porcentagens nos históricos pertencem às suas respectivas etapas.

<!-- /coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:9480eefca023976c4f7041f1b1bffd34d52dca6b6dbd1d40f952f3f4cbd8ca31 -->

## Histórico — Frente 3 (2026-11-20)

<!-- /coderabbit-ai-generated:v1 -->

**Cobertura funcional local: 9,1% (1/11 frentes com implementação integrada e testes locais nesta continuação).** O denominador são as onze frentes das diretrizes, com peso igual; não é estimativa de esforço, de RAM medida ou de prontidão de produção. As dez restantes continuam pendentes, incluindo RSI e PIANO. O aceite em CI e aparelho é acompanhado separadamente. A métrica numérica foi introduzida por solicitação posterior do proprietário, sem inferir avanço por número de arquivos.

- Tsetlin C++20 de tamanho fixo: 16 cláusulas, 16 bits de entrada, literais positivos/negados, 64 estados por decisão, feedback I/II com especificidade 3 e limiar de voto 4. Early-exit preserva a classificação fora do limiar; o voto parcial não é apresentado como soma exata de todas as cláusulas.
- Ponte JNI versão 2 com 18 assinaturas conferidas. As duas novas chamadas validam dimensões, cabeçalho, bits e intervalos. O processamento C++ da Tsetlin não usa heap, handles ou pools globais: estado de 516 bytes e arrays locais fixos. Falha na alocação do array Java conserva a exceção JVM pendente.
- NativeMind treina com resultados reais, semente determinística e features da decisão anterior; usa o classificador na prioridade das ações. O estado imutável entra no evento/checkpoint e é projetado em tsetlin_automata_blobs na mesma transação. Auditoria compara digest e bytes com o evento canônico; fork copia somente o prefixo histórico. Defaults omitidos conservam a codificação dos eventos antigos.
- scripts/test-native.sh passou: dez suítes funcionais com ASan/UBSan, mais a injeção de falhas HNSW. Teste Tsetlin cobre XOR, determinismo, serialização/corrupção e as 65.536 entradas para equivalência da classificação com early-exit. Uma execução adicional do teste ampliado passou com 10% de rótulos ruidosos.
- scripts/test-mind-jni.sh passou: classificador JNI, estado restaurado, entradas inválidas, biblioteca ausente/presente, 500 transições canônicas, live/shadow, busca/isolamento/cancelamento, idade Double. Nenhuma política foi promovida na busca de regressão.
- :core:test :host-validation:check testDebugUnitTest lintDebug passou. Sete testes SQLite passaram, incluindo disco cheio, corrupção e rollback após constraint violation envolvendo evento e blob. Os testes instrumentados Android continuam sem execução em aparelho.
- O workflow Android CI executa em cada push e manualmente. Passa a exigir quatro APKs Debug e quatro Release, publicar Girassol-debug, Girassol-release e Girassol-checksums por 30 dias e explicar o download no resumo. O YAML foi validado localmente. Aprovação remota e novos APKs não são presumidos a partir dessa validação.
<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:5f9c02a8cd1da160109e6a20ae9f0bb0ea81ae10b5d46fe4c6ba2d8aafda86c0 -->

- Build local Debug/Release da Frente 3 passou; os oito APKs passaram em apksigner verify e as 18 exportações JNI foram verificadas nas três ABIs. CI remoto do commit dafbcb3aa64be702380d0826c0d1b7174939edad terminou com sucesso: https://github.com/conta14dinheiro-droid/Girassol-v2.0/actions/runs/44646717512 . Não foi medida a latência alvo nem o consumo total no Moto G62 de 4 GB; 150 estados binários ativos da Tsetlin somam 77.400 bytes, excluindo cópias Kotlin/eventos e demais motores.

<!-- /coderabbit-ai-generated:v1 -->

O texto abaixo preserva o histórico das etapas anteriores.


<!-- /coderabbit-ai-generated:v1 -->

## Alterações desta etapa

- PR #1 integrado à branch da tarefa.
- Regras v8 para nascimentos: limite de admissão de 150 habitantes, IDs limitados por hash e segregação dos alelos com a dominância correspondente. É um orçamento conservador; não constitui medição de RAM em 4 GB. Eventos v1–v7 continuam com suas regras históricas.
- Serviço e Shadow-Replay usam CURRENT_RULES_VERSION. Métricas contam observações por população efetiva de cada tick. Promoções registram versão, e o coordenador recusa evidência de outra versão. O limiar legado >0.03 foi preservado.
- Idade acumula em Double no Kotlin; C++ deixa de integrar idade em float. Exceções de alocação na ponte JNI são traduzidas para Java.
- Fusão de opiniões dogmáticas idênticas preserva a opinião; a política existente para conflitos permanece. Imunidade ainda não está integrada ao fluxo de assimilação.
- Módulo host-validation compila as classes reais da aplicação que não dependem de Android. O script JNI inclui NativeAutonomy e executa aprendizagem/replay, busca RSI, equivalência live/shadow e teste de idade/entrada inválida.
- CI executa esse script e volta a acompanhar synchronize de PRs.

## Próximos requisitos, ainda pendentes

1. RSI delimitado com CognitivePolicyGenome/VPO, gates H_sem/Brier e ledger completo.
2. Integração de opiniões/proveniência, fusão por média, A-MEM e SWR real.
3. Tsetlin persistente, feedback I/II, early-exit, JNI e treinamento integrado.
4. Biblioteca Voyager com requisitos de execução e domínio; FSRS/IRT completos.
5. F/G(pi), PIANO, PSelf/Top-3 e gate Tsetlin conectado.
6. Dunbar/Beta por domínio, dinâmica social híbrida, Heider, ADICO e PSI.
7. MAP-Elites/AURORA com Oja/Gram-Schmidt e nichos 3D.
8. Ledger contábil, commons, difusão, TTL, Smart Terrain e jobs que utilizem anexos.
9. Baldwin/simbiogênese e diversidade semântica validada.
10. Recast/ORCA, HRTF/oclusão/ducking e Vulkan com fallback.
11. Treino LoRA real com LoRA+, AdamW, EWC/Fisher e persistência.

Também faltam migrações das oito tabelas, contrato libgirassol_core/GeaNativeBridge, testes de falhas SQLite e carga 24/7 no alvo. Release ainda usa chave debug; distribuição atualizável requer uma identidade de assinatura persistente. Nenhum desses itens deve ser anunciado como concluído por existir um nome de classe ou teste isolado.

<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:fa608dd4e897c17169c6542b9b3aa87d335cd6913c86837b952b993d873d9094 -->

## Evidências desta execução — 2026-11-19

O texto acima é o estado inicial fornecido pelo proprietário. A base oficial `3085e58b14811d38eeb7d5e2f0c784af0d25d975` foi integrada por fast-forward. **As cinco fases e as onze frentes ainda não estão concluídas.**

### Fase 1 em andamento

- Schema 5 aditivo: oito tabelas com chaves para `events`, índices, limites de payload/blob e restrições de domínio. `WorldStore` executa criação/migração, copia o prefixo das tabelas nas bifurcações e verifica integridade SQLite e referências na auditoria. O checkpoint e os eventos históricos mantêm seus formatos.
- Promoções RSI já existentes e mudanças dos traços de aprendizagem são projetadas nas tabelas na mesma transação do evento. Isso não implementa novos gates RSI nem torna o algoritmo pedagógico existente um FSRS completo. As outras seis tabelas ainda aguardam seus produtores/consumidores nas fases seguintes; a migração não fabrica dados históricos.
- `GeaNativeBridge` centraliza carregamento e versão de ABI de `libgirassol_core`. Os símbolos JNI existentes foram preservados. `libgirassol_llama` continua separada; a consolidação integral do contrato e a validação de todas as entradas ainda estão pendentes.
- O tratamento de corrupção sinaliza erro e preserva o arquivo. Testes instrumentados novos cobrem upgrades 1–4 e retenção do arquivo corrompido, mas ainda não foram executados em Android.

### Execuções observadas

- `python3 scripts/test-gea-sqlite.py`: seis testes passaram com SQLite real. Cobertura: FK/domínios/limites, preservação de eventos, rollback após migração interrompida, `SQLITE_FULL` real com rollback de evento/blob, arquivo corrompido e bifurcação por prefixo. Uma primeira execução revelou erro transacional no harness Python; corrigido com `BEGIN` explícito, e as execuções posteriores passaram.
- `mise exec java@temurin-17 -- ./gradlew -Dorg.gradle.configureondemand=true :host-validation:writeHostClasspath :core:test --no-daemon`: sucesso; 91 testes do core. O código do core permanece idêntico à base confirmada.
- `scripts/test-native.sh`: nove suítes passaram com ASan/UBSan (projection, city_mesh, synth, plasticity, vector_memory, semantic_index, cma_es, dataset_decoder, anokhin_gnw_core).
- JDK 17, SDK35, Build Tools35.0.0, NDK27.0.12077973, CMake3.22.1 e Filament1.56.0 preparados. O arquivo Filament foi conferido pelo SHA256 fixado no CI. A primeira execução Gradle falhou por confiança TLS; o uso da CA existente do sandbox corrigiu o download.

### Pendências estruturais identificadas

<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:0f2f8e40a2b04b67b96eebd30aabdef344e5e12b9178056f1796317860555077 -->

- O caminho HNSW de busca episódica/semântica passou a permitir exceções, liberar alocações interrompidas e traduzir falhas na ponte JNI de memória. O limite episódico é de 2048 entradas. A injeção determinística de falhas em `new`, `malloc` e `calloc` recuperou 36 posições de alocação episódica e 34 semântica, sob ASan/UBSan. A suíte nativa completa posterior também passou. Isso não comprova todas as alocações/handles JNI dos demais motores; a cobertura integral e a medição no alvo continuam pendentes.

<!-- /coderabbit-ai-generated:v1 -->
<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:37e640cb500fbd51db897a5bbbceaf24e08ecb36f92d07dddac02eaecec6e56d -->

- A rodada JNI final passou integralmente: disponibilidade com/sem biblioteca, 500 transições reais, replay/search/isolation/cancellation e integração de idade/entrada inválida. A conferência estática validou as 16 assinaturas Kotlin/C++ e os 16 símbolos nas três ABIs dos APKs universais Debug e Release. Isso ainda não cobre todas as entradas inválidas e falhas de alocação de todos os motores.
- O comando solicitado `ANDROID_HOME="$HOME/runtime/android/sdk" FILAMENT_TOOLS_DIR="$HOME/runtime/filament/1.56.0" mise exec java@temurin-17 -- ./gradlew testDebugUnitTest lintDebug :app:assembleDebug :app:assembleRelease --no-daemon` terminou com sucesso em 14m43s, com 122 tarefas. Os 13 testes unitários Android passaram e o lint passou.
- Foram gerados e verificados por `apksigner verify` os oito APKs: Debug/Release para arm64-v8a, armeabi-v7a, x86_64 e universal. Permanecem em `app/build/outputs/apk/{debug,release}/`. Release usa chave debug, como na configuração inicial; isso não estabelece uma identidade de distribuição persistente.
- `:app:compileDebugAndroidTestKotlin` passou, incluindo os novos testes de migração/corrupção. A execução desses testes em Android permanece pendente. O build e os testes realizados não comprovam a conclusão funcional das onze frentes.

<!-- /coderabbit-ai-generated:v1 -->
- Sem `/dev/kvm` neste sandbox; nenhuma medição 24/7, de RAM no Moto G62 ou validação de dispositivo foi realizada.
<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:23cbead76df765fcca357aed87b8a0749a9492e5324a8f831da5b40824e0154a -->

- Fases 2–4 permanecem pendentes conforme a lista inicial. Nenhuma classe/tabela criada nesta etapa comprova a conclusão dessas frentes.

<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:7fb9cca374902ba3700409832f07fcc06f64b57915a850c2c8ccafe248470f23 -->

<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:50987d8d6a4eac11bd859603a7e2fd46fa86b98baf09c728d487bbe129a8f779 -->

## Histórico — Frente 5 (2026-11-20)

<!-- /coderabbit-ai-generated:v1 -->

**Cobertura funcional local: 18,2% (2/11 frentes: 3 e 5).** Mantém o critério de peso igual explicado acima; não representa esforço nem aceite de RAM/24 horas em aparelho. As nove outras frentes continuam pendentes. A seção anterior descreve o checkpoint da Frente 3.

- Inferência ativa enumera um modelo generativo de dois estados de prontidão e duas observações de sucesso. Calcula posterior Bayesiano, F = KL(q||p) − E_q ln p(o|s) e G = risco + ambiguidade = custo pragmático − informação esperada. É planejamento de um passo para as oito atividades existentes; não é um modelo de linguagem ou horizonte arbitrário.
- PIANO executa cinco módulos reais de percepção, memória, social, planejamento e motor, com workspace compartilhado por chamada, barreira e soma em ordem fixa. Dois workers e fila de dez tarefas limitam concorrência; sobrecarga executa no chamador. Trabalhadores recebem cópia dos oito escores nativos, sem reter o buffer JNI reutilizável.
- CoreSelfAnchor/PSelf protege hidratação, glicose e energia críticas; Top-3 e histerese entram na seleção cognitiva. Escores completos Tsetlin informam verossimilhanças; early-exit prioriza políticas e evita chamadas LLM rotineiras confiantes. O bloqueio térmico existente permanece. WorldActor conserva sua arbitragem GOAP e revalida as pré-condições físicas antes da execução.
- F/G, probabilidades, Top-3 e decisão de solicitar LLM são persistidos no mesmo evento canônico da mente; defaults omitidos mantêm a codificação histórica. Nenhuma mudança no teto v8, alelos, IDs, idade Double ou CURRENT_RULES_VERSION.
- Testes locais: 94 do core e 13 do app; sete testes SQLite; 18 assinaturas JNI. host-validation e lintDebug passaram. scripts/test-native.sh passou com dez suítes ASan/UBSan e injeção HNSW. scripts/test-mind-jni.sh passou novamente após a correção de concorrência: 500 transições, live/shadow de 256 ticks, 21 avaliações, isolamento/cancelamento e idade/entradas inválidas. Nenhuma promoção RSI foi fabricada.
- Comando completo testDebugUnitTest lintDebug :app:assembleDebug :app:assembleRelease --no-daemon passou em 6m17s (121 tarefas). Os oito APKs passaram em apksigner verify; as 18 exportações foram conferidas nas três ABIs do universal Release. Release segue usando chave debug. CI deste novo commit será disparado pelo push; sucesso remoto não é presumido.
- Limites ainda sem medição: latência, RAM total e carga 24/7 no Moto G62; testes instrumentados Android não executados por ausência de dispositivo.

<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:e0a1af0de856bd0de106f695e3a7e5d131ed0279b7ab0d0c837c3d386fde32cf -->

<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:209dddce9051bce7b9b6158a648ab7b6460002f4d3df3c9733fdd09df6872c09 -->

## Histórico — Frente 1 / RSI (2026-11-20)

<!-- /coderabbit-ai-generated:v1 -->

**Cobertura funcional local: 27,3% (3/11 frentes: 3, 5 e 1).** São frentes implementadas e integradas com os testes locais descritos; não é percentual de esforço, de cobertura de linhas ou de aceite no aparelho. As oito frentes das Fases 3 e 4 continuam pendentes. A próxima é a Frente 8.

- CognitivePolicyGenome limita sete parâmetros aos intervalos da referência; rejeita valores inválidos em vez de corrigi-los silenciosamente. Os parâmetros afetam exploração, gate de deliberação, histerese, atualização MemRL, decaimento ACT-R na consolidação e sensibilidade térmica. Pesos físicos/limites do mundo não são mutados.
- VPO compila uma gramática GBNF finita em 27 regras condicionais permitidas. O compilador rejeita recursão, símbolos desconhecidos, construções não suportadas e expansões acima de 128 strings/140 caracteres. Genomas conservam no máximo oito regras. CMA-ES nativo usa suas oito dimensões fixas para sete parâmetros e seleção de produção gramatical; não gera código executável.
- Replay usa WorldActor/NativeMind reais e a mesma versão corrente. Mede F médio, fração de agentes homeostaticamente viáveis, Brier apenas em resultados concluídos (MOVE não é rotulado como falha), entropia normalizada das atividades e chamadas neurais efetivas. A busca determinística não chama o LLM/research assíncrono: N_LLM foi zero, e isso não mede o custo do LLM em produção.
- Promoção exige zero violações, H_sem ≥ 0,35, degradação Brier ≤ 0,010 e ΔJ ≥ 0,025 nas janelas de treino e validação independentes. Também mantém ganho legado >0,03 sem regressão nas quatro métricas. Regras v8, idade Double e CURRENT_RULES_VERSION não foram alterados. Revisões históricas mantêm a codificação e validação legadas.
- Schema 6 acrescenta rsi_policy_trials sem modificar as oito tabelas de domínio do Schema 5. Propostas são gravadas antes de executar a avaliação; treino e resultados são registros imutáveis, com FK para a origem, digest, limite de 16 KiB e unicidade do terminal. O tick de gravação separa evidências futuras em forks. Recuperação marca propostas interrompidas em lotes de vinte. Promoções novas exigem evidência VERIFIED persistida e correspondência com o treino registrado.
- A integração do serviço grava cada proposta/resultado, aguarda a finalização cooperativa antes de fechar/trocar a linha e bloqueia novos trabalhos após close. VERIFIED significa experimento verificado; a promoção efetiva continua no evento e em rsi_promotion_ledger, na transação do checkpoint.
- Execuções locais finais: 97 testes core, 13 do app, host-validation e lintDebug passaram. Os onze testes SQLite reais passaram, incluindo disco cheio, corrupção, rollback, migrações 5/6, imutabilidade e cópia de prefixo com FK diferida. As dez suítes nativas e a injeção HNSW passaram com ASan/UBSan.
- JNI final passou: 500 transições, equivalência live/shadow com genoma alterado, repetição determinística, vinte propostas com vinte resultados terminais, cancelamento preservando proposta e recusa de avaliação após falha do gravador. Todos os candidatos dessa busca foram recusados em treino; não houve promoção nem execução da janela de validação de um vencedor nessa rodada. Testes de unidade cobrem aceitação/recusa das duas janelas; não foi fabricado um vencedor.
- Build com core/host/instrumentation compile/testDebugUnitTest/lintDebug/assembleDebug/assembleRelease passou em 7 minutos, 137 tarefas. Os oito APKs passaram em apksigner verify; 18 símbolos JNI foram conferidos em todas as três ABIs do universal Release. Release continua com chave debug. O push dispara nova Action; sucesso remoto deste commit ainda não é presumido.
- Testes instrumentados de migração 1–5→6 e recuperação real do WorldStore compilaram, mas não executaram: adb não encontrou dispositivo, a lista de AVDs está vazia e /dev/kvm não existe. A verificação em software não foi iniciada sem emulador/imagem instalados. RAM total/latência/24 horas no Moto G62 continuam sem medição. Os limites novos de genoma, gramática, candidatos e payload não substituem essa medição nem fecham os limites dos motores das próximas frentes.

<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:2e234e4823f94e24069e3d17a9171b32c081de9c67c23d3dc498a6ad7e7dfe7a -->

<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:a33fa7493950057372c53d61961654ec6f33899b39818e161a0a721742dbb62e -->

## Histórico — Frente 8 / Economia e mundo (2026-11-20)

<!-- /coderabbit-ai-generated:v1 -->

**Cobertura funcional local: 36,4% (4/11 frentes: 3, 5, 1 e 8).** A métrica conta frentes implementadas, integradas e verificadas localmente com o mesmo peso; não representa esforço, cobertura de linhas ou aceite no Moto G62. Permanecem sete frentes: 2, 6, 4, 7, 9, 10 e 11.

- Base desta entrega: `0b4523bc6210b4040178850acd62b26b16631dfb`. A Action desse commit, execução `44651559465`, terminou com sucesso e publicou Girassol-debug, Girassol-release e Girassol-checksums. O workflow existente dispara em push, PR e manualmente; orienta escolher app-arm64-v8a-debug.apk no Moto G62. Sucesso remoto da Frente 8 depende da execução iniciada pelo próximo push.
- WorldActor registra toda transferência aceita como débito/crédito conservativo e confere a reconstrução dos saldos antes de publicar. Bens comuns recebem contribuições e recursos reciclados, financiam alimentação de habitantes sem moedas e fornecem água/alimento disponíveis; não há emissão de recursos/moedas. GOAP e execução consultam os fundos comuns e os custos das ferramentas. O teste de insolvência encontrou e corrigiu uma escolha indevida de WRITE antes do auxílio disponível.
- Ferramentas de escrita/construção imobilizam madeira real, duram 32 usos e devolvem material ao ambiente quando quebram. Construção consome quatro unidades adicionais e produz anexo utilizável. Smart Terrain anuncia as atividades dos anexos reais e direciona o solver JNI ao cômodo escolhido. Jobs registram reserva, deslocamento, execução, recibo e interrupção, com referência à experiência anterior quando existente. Reservas têm TTL de 30 ticks e capacidade do cômodo; falhas/interrupções liberam a reserva.
- Infraestruturas ficam no checkpoint, reduzem o custo metabólico nas proximidades e expiram em 2.400 ticks, removendo o anexo físico e devolvendo a madeira ao ambiente. Estoque estrutural anterior é preservado em legacyTimber sem inventar autoria ou expiração retroativa. IDs novos de anexos incluem hash para evitar colisão após expirações.
- Estigmergia usa oito canais de 12×12 células, difusão de cinco pontos, bordas refletivas e evaporação de 3% por tick. Células de quatro metros cobrem 48×48 metros e os 128 terrenos procedurais testados. Foi corrigida a projeção inicial que concentrava anexos na borda. Os sinais influenciam a pontuação cognitiva. Limites adicionais: 150 jobs/reservas, 300 ferramentas, 128 infraestruturas e 1.024 transferências por tick. Esses limites não comprovam RAM total em 4 GB.
- As tabelas double_entry_ledger e ecological_niche_structures recebem os registros na mesma transação do evento/checkpoint. A auditoria confere conteúdo, ordem e quantidade contra o evento canônico, detectando registros alterados ou ausentes. Não houve migração adicional: são produtores das tabelas do Schema 5. Produtores completos de crenças, instituições e biblioteca Voyager continuam associados às próximas frentes.
- Novos campos de estado/evento e a ativação econômica têm defaults omitidos; eventos históricos sem ativação mantêm o caminho anterior. Permanecem CURRENT_RULES_VERSION=8, teto de admissão de 150 habitantes, alelos, IDs de descendentes por hash e idade Double. Testes históricos e de reprodução continuam passando.
- Verificação final local: **105 testes core, 13 do app, 12 testes SQLite reais**, host-validation e lintDebug aprovados. SQLite inclui migração, corrupção, disco cheio, constraints, rollback e atomicidade de ledger/infraestrutura. As dez suítes nativas e injeção HNSW passaram com ASan/UBSan. As 18 declarações JNI correspondem ao C++ e às exportações nas três ABIs.
- scripts/test-mind-jni.sh final passou: contrato ausente/presente, 500 transições reais com replay exato, construção/ferramenta/ledger/anexo no solver C++, equivalência live/shadow com economia ativa, 21 avaliações RSI, isolamento, cancelamento, recusa de escrita e idade/entradas inválidas. Nenhum dos vinte genomas passou por todos os gates de treino; não houve promoção fabricada. A primeira versão do teste de construção pressupunha trabalho no primeiro tick; o teste foi corrigido para exigir chegada física efetiva e continuou passando pelo fluxo real.
- Comando final `:core:test :host-validation:check :app:compileDebugAndroidTestKotlin testDebugUnitTest lintDebug :app:assembleDebug :app:assembleRelease --no-daemon` passou em **5m35s**, 137 tarefas. Os **oito APKs** corrigidos passaram em apksigner verify. Release ainda usa chave debug; identidade persistente para distribuição atualizável continua pendente.
- Testes instrumentados de WorldStore, reabertura e remoção indevida de lançamento compilaram, mas não executaram: continuam ausentes dispositivo/AVD/KVM. Não foram medidos RAM total, latência ou operação 24 horas no alvo. A Frente 2 é a próxima implementação.

## Entrega atual — Frente 2 / Memória, crenças e consolidação (2026-11-20)

**Cobertura funcional local: 45,5% (5/11 frentes: 3, 5, 1, 8 e 2).** Conta frentes implementadas, integradas e verificadas localmente com peso igual. Não representa esforço, cobertura de linhas, verdade semântica dos dados externos ou aceite no Moto G62. Permanecem seis frentes: 6, 4, 7, 9, 10 e 11.

- Base: `7c7d29cab4a25c0a9b8445677d38512eef55d46d`, cuja [Action 44655703000](https://github.com/conta14dinheiro-droid/Girassol-v2.0/actions/runs/44655703000) terminou com sucesso e publicou os três artefatos de APKs/checksums. O workflow dispara em cada push e manualmente; a aprovação remota desta entrega será conferida separadamente após o push.
- A-MEM associativa sobre as observações estruturadas de 16 dimensões já produzidas pela aplicação: até 512 episódios por agente, quatro vizinhos por episódio e 2.048 arestas. Ligações antigas são podadas com os episódios. Ativação dos sete itens de memória de trabalho expande os candidatos da recuperação HNSW antes da seleção final MemRL; teste JNI comprova recuperar uma associação fora do Top-1 inicial. Não são apresentados como embeddings de linguagem.
- Recuperação reutiliza buffers diretos de capacidade fixa de 163.904 bytes por thread para até 2.048 observações na interface existente. Views com tamanho exato preservam o contrato JNI; a compilação Android encontrou e corrigiu o encadeamento de métodos Buffer incompatível com os stubs Android. O teste de reutilização verifica que consultas não herdam resultados anteriores. Alocações nativas HNSW continuam limitadas e protegidas pelas exceções da ponte.
- Crenças preservam hash da linha, aquisição original, dataset, domínio, revisor e checkpoint/evento de origem. Cópias sociais preservam os dados brutos e usam a confiança direta do destinatário na fonte original. São no máximo 32 afirmações com oito origens cada, sem ganhar confirmação independente pela circulação entre agentes.
- Fusão por média ponderada opera dentro dos componentes que compartilham dataset **ou** checkpoint de origem, incluindo dependência transitiva. Só componentes separados usam fusão cumulativa. Isso é um modelo conservador de dependência estrutural, não prova de independência causal ou veracidade. A fórmula degenerada da referência que produzia b+d+u=0 foi substituída pela forma normalizada equivalente fora da degenerescência; opiniões vacuosas continuam vacuosas e o conflito dogmático mantém a política anterior de incerteza.
- Pesquisa isolada e assimilação ao vivo chamam o mesmo gate antes do estudo nativo: procedência válida, fonte não repetida, confiança mínima 0,2, probabilidade projetada mínima 0,62 e incerteza máxima 0,8. O tensor atual combina relação Beta geral e competência da fonte no domínio; a dinâmica Beta separada por domínio pertence à próxima Frente 6. Recusas ao vivo atualizam a quarentena para IMMUNITY_REJECTED. O teste JNI comprova rejeição antes de alterar pesos e estudo real após aceitação.
- SWR executa pulsos Hebbian/LTC reais durante sono físico e condição térmica leve: até 12 episódios por agente a cada 30 ticks e no máximo dois agentes por tick. Persistem fontes, número de pulsos e digests dos pesos antes/depois. Pesos realmente mudam; estado/scores da ativação desperta conservam sua cópia antes da reutilização do buffer. O replay gera até oito esquemas por ação, com até 32 referências por esquema e contagem sem duplicar episódios já consolidados; essas expectativas participam da decisão PIANO. O SWR desta frente não é treino LoRA, ainda pendente na Frente 11.
- Assimilação física aceita o anexo de pesquisa escolhido pelo Smart Terrain e recusa anexo expirado ou agente sem trabalho presencial. As opiniões novas são projetadas em subjective_beliefs na transação do evento/checkpoint; o banco confere a aquisição/revisões originais. A auditoria compara componentes descontados e quantidade contra o evento canônico, verifica o evento de origem e impede que a cópia altere sua evidência original. Não houve migração de DDL adicional. Instituições e biblioteca Voyager ainda aguardam seus produtores completos nas Frentes 6 e 4.
- **Validação local: 115 testes core, 13 do app e 13 testes SQLite reais aprovados**, além de host-validation e lintDebug (zero erros, 27 avisos existentes). SQLite inclui constraints, duplicação de origem e rollback do evento ao inserir opinião inválida. As dez suítes nativas e as 36+34 posições de falha de alocação HNSW passaram com ASan/UBSan.
- scripts/test-mind-jni.sh passou: contrato ausente/presente, 500 transições com replay exato, nova integração de memória, economia física, live/shadow determinístico de 256 ticks, 21 avaliações RSI, contabilização das vinte mutações/terminais, isolamento, cancelamento, falha de escrita, idade Double e entradas inválidas. Os vinte candidatos foram rejeitados em treino; nenhuma promoção foi fabricada. A integração de memória foi repetida separadamente sobre o código final compatível com Android e passou.
- Build final `:core:test :host-validation:check :app:compileDebugAndroidTestKotlin testDebugUnitTest lintDebug :app:assembleDebug :app:assembleRelease --no-daemon` aprovado em **5m10s**, 137 tarefas. Os **oito APKs** passaram em apksigner verify; 18 declarações/exportações JNI conferidas nas três ABIs. Release mantém a chave debug de desenvolvimento.
- Teste Android do WorldStore com aquisição real, gate, falha forçada na escrita da crença, rollback da quarentena/evento e detecção de projeção ausente **compilou, mas não executou**. Continuam ausentes dispositivo/AVD/KVM. Não foram medidos RAM total, latência ou operação 24 horas no Moto G62. Regras v8, admissão de 150 habitantes, alelos, IDs por hash, idade Double e CURRENT_RULES_VERSION=8 foram preservados. A próxima implementação é a Frente 6.



<!-- /coderabbit-ai-generated:v1 -->



<!-- /coderabbit-ai-generated:v1 -->



<!-- /coderabbit-ai-generated:v1 -->

<!-- /coderabbit-ai-generated:v1 -->


<!-- /coderabbit-ai-generated:v1 -->
