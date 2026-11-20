# Continuação do Girassol — estado verificável

Objetivo solicitado: completar as 11 frentes de `knowledge-base/girassol-diretrizes.md`. **Objetivo ainda não atingido.** Este registro não atribui percentual por contagem de arquivos.

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
- Fases 2–4 permanecem pendentes conforme a lista inicial. Nenhuma classe/tabela criada nesta etapa comprova a conclusão dessas frentes.


<!-- /coderabbit-ai-generated:v1 -->


## Incremento adicional — 2026-11-20

- Foi adicionado `AuroraMapElites.kt`: arquivo MAP-Elites 3D determinístico, com uma elite por nicho, métricas de replay validadas, vínculo a evento comprometido, regra estável de desempate, inserção imutável e métricas de cobertura/qualidade.
- O mesmo módulo inclui um projetor online Oja de três componentes, com média incremental, atualização Oja, ortogonalização Gram-Schmidt e descritores limitados a `[0,1]`.
- Foi adicionado `AuroraMapElitesTest.kt`, cobrindo substituição por candidato melhor, nichos distintos, idempotência, rejeição de evidência inválida, inicialização determinística, ortogonalidade e serialização.
- **Limite explícito:** este incremento é o núcleo matemático/arquivo. Ainda não está conectado ao ciclo RSI/geração de genomas, não possui persistência em GEA/SQLite, nem implementa o ciclo completo AURORA de exploração, mutação, seleção e avaliação em ambiente. A frente MAP-Elites/AURORA segue parcialmente pendente até essas integrações e validação.


## Continuação técnica — 2026-11-20 (incremento atual)

### Integrações concluídas em código nesta rodada

- **RSI / evidência de promoção:** `ReplayMetrics` recebeu campos versionados de Brier e entropia semântica. Shadow-Replay calcula Brier sobre as cinco necessidades previstas versus observadas e H_sem sobre a distribuição de ações. Uma promoção com evidência v1 precisa manter o ganho de fitness acima de 0,03, não regredir Brier em mais de 0,005, preservar H_sem dentro de 0,015 e respeitar o piso absoluto H_sem ≥ 0,35. Eventos antigos sem os novos campos continuam decodificáveis como evidência v0; evidência v0 não pode ser misturada com v1 numa promoção nova. **Ainda falta** migrar o otimizador para mutação exclusiva de CognitivePolicyGenome e regras VPO/GBNF.
- **Economia de dupla entrada:** cada evento persistido agora materializa os deltas líquidos por conta/recurso em `double_entry_ledger`, pareando débitos e créditos de modo determinístico na mesma transação do evento. Teste instrumentado reconcilia cada conta/recurso com o delta exato do WorldActor em 80 transições. Isso prova os movimentos líquidos por evento; não representa cada transferência intermediária interna separadamente.
- **Voyager Skill Library:** procedimentos com pelo menos três sucessos e probabilidade BKT ≥ 0,75 são escritos em `voyager_skill_library` com pré-condições, programa declarativo, execução e proveniência vinculada ao evento. A auditoria reconstitui o procedimento e o traço BKT do evento canônico. Há teste instrumentado de persistência/proveniência.
- **Memória / replay:** o lote de sono agora considera Q TD específico por evento para priorizar a reprodução. Em sono concluído e com margem térmica suficiente, até 4 ou 12 episódios (conforme termalização) passam pelo atualizador LTC nativo; há orçamento global por tick proporcional à população. Teste unitário cobre a influência de Q na ordem determinística. Isso é replay priorizado funcional, mas não uma implementação neurofisiológica completa de Sharp-Wave Ripple nem de A-MEM.
- **QD / AURORA:** o núcleo contém arquivo MAP-Elites 3D com uma elite por célula, desempate determinístico, inserção imutável e validação de proveniência; projetor Oja online de três componentes com Gram-Schmidt. O GEA persiste candidatos e checkpoints do projetor em `ecological_niche_structures`, com validade temporal e auditoria. Todos os habitantes de um mesmo tick usam a mesma base congelada; a atualização Oja ocorre após a projeção do lote. **Ainda falta** o ciclo autônomo completo de geração/mutação/avaliação/seleção e a limpeza física de registros expirados.
- **Genética:** rules v9 adiciona locus de simbiose e transmissão Baldwiniana determinística, com viés adaptativo limitado pelo domínio aprendido dos progenitores, mutação limitada e mistura simbiótica somente quando há complementaridade e domínio suficiente. Eventos v1–v8 preservam o algoritmo genético anterior. Testes cobrem determinismo, viés, limites e payload legado.
- **Espaço:** o solver espacial agora calcula velocidades ORCA recíprocas em passo fixo, limita a 16 vizinhos próximos e mantém o teste de varredura de paredes/correção de sobreposição como barreira final. O teste C++ de aproximação frontal, limite de velocidade, reprodutibilidade e sobreposição foi incluído no script ASan/UBSan.
- **Áudio binaural:** o Synth aplica espacialização ITD de Woodworth e ILD aos sinais de movimento, com atenuação por distância e filtro de oclusão disponíveis no processador; a trilha Trautonium recebe ducking sidechain dos passos. O teste nativo mede ITD, ILD, distância e atenuação de altas frequências. **Limite atual:** o pipeline do mundo ainda passa distância/oclusão como zero; raycast de paredes e posição 3D real da fonte ainda não alimentam esses parâmetros.

### Evidência de CI e correção

- A execução `44729374401` passou a preparação, compilação Kotlin do módulo core, testes C++ ASan/UBSan, SQLite e checagem das assinaturas JNI, mas falhou ao compilar a integração JNI de produção por inicialização ambígua de `std::vector<orca::Vec2>`. A linha foi corrigida explicitamente para `orca::Vec2{}` no commit `775a186`.
- A nova execução para o commit `775a186` foi aberta como run `44729997356`; seu estado permanece pendente no último registro. Não declarar a HEAD atual validada nem publicar APK correspondente antes do resultado completo.
- Os testes instrumentados de ledger/Voyager/AURORA foram adicionados, mas ainda aguardam execução Android no CI. A suíte HRTF também foi adicionada depois da execução que falhou e aguarda validação.

### Lacunas que continuam abertas

- RSI ainda não implementa CognitivePolicyGenome + VPO/GBNF como único espaço de mutação.
- Memória ainda precisa de A-MEM e de um modelo Sharp-Wave Ripple explícito; replay LTC atual é um mecanismo funcional aproximado e limitado por orçamento.
- Sociedade continua sendo uma projeção v0.1; instituições Ostrom persistentes, confiança Beta por domínio e dinâmica institucional completa seguem pendentes.
- Economia ainda carece de COMMUNAL_COMMONS interoperável, campo feromonal com difusão/evaporação O(1), Smart Terrain, ReservationManager com lease TTL e WorkGiver→Job→Toil.
- MAP-Elites ainda não executa o ciclo autônomo completo de busca evolutiva.
- Oclusão/atenuação por distância HRTF ainda não consome raycast e posição real do mundo; ducking e cues binaurais estão integrados.
- GPU compute OpenCL/Vulkan/KleidiAI, pinagem de threads e ADPF continuam sem ativação real. Vulkan no Filament continua dependendo de teste em dispositivo.
- LoRA real (forward/backward no modelo, LoRA+, AdamW, Fisher/EWC e persistência) continua pendente.
- Nenhuma medição de 24/7, RAM no Moto G62, thermal throttle, áudio ou renderização física foi feita nesta rodada. Assinatura de distribuição permanente ainda depende de chave protegida.


## Incremento de integração social e áudio — 2026-11-20

- **Instituições ADICO:** `GeaSchema.record` grava por evento o catálogo normativo inicial do `biosphere-commons`: regras distintas deduplicadas por deôntica, ação, condição e consequência. Os estados `MUST/MAY/MUST_NOT` são mapeados para `OBRIGADO/PERMITIDO/PROIBIDO`; o ID é um hash determinístico dos campos e a auditoria recalcula esse hash. Teste instrumentado verifica sete regras e os três tipos deônticos. Isso persiste o catálogo de regras já existente, mas ainda não implementa negociação, sanção, votação ou revisão institucional por agentes.
- **Áudio sidechain:** o ducking da trilha agora responde ao envelope transitório dos passos, com ataque de 18 ms e liberação de 240 ms, em vez de depender só da amplitude instantânea do ruído.
- **CI intermediário:** a execução `44729997356` passou em integração JNI de produção, testes Kotlin/lint e compilação dos testes instrumentados Android; o build dos APKs ARM64 ainda estava em andamento na última consulta. Ela valida o commit `775a186`, não contém as últimas alterações de HRTF/ADICO/sidechain.
- **CI da HEAD mais recente:** a execução `44730545295` foi criada para o commit `75a505d7`; ainda aguardava início de job na última consulta. A correção posterior de sidechain e este registro exigem também CI na HEAD subsequente. Não marcar a branch inteira como aprovada até todos os gates atuais concluírem.
