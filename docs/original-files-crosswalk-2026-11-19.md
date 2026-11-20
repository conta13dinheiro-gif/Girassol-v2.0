# Auditoria cruzada dos arquivos originais — 19/11/2026

## Corpus recuperado

Na Biblioteca acessível desta execução foram recuperados:

- **A geração autom.txt** — especificação GEA v2.2: WorldCore em 12 estágios, linguagem emergente, conversas e vínculos diegéticos, rotinas, memória em camadas, relações sociais, biologia/BKT, Tsetlin, governança de IA e apresentação.
- **1. Ponte JNI C+.txt** — blueprint JNI/C++20 com cerca de 6.869 linhas, incluindo motor autônomo, mente individual, grade memética, Filament/Dehancer, direção de câmera, áudio liminar, serviço persistente e ponte llama.cpp/GBNF.
- **Girassol RSI & Fronteiras — Pesquisa Profunda, Hipóteses e Implementação Nativa.md** — seis hipóteses H1–H6: RSI delimitado, consolidação e imunidade epistêmica, alostase térmica, anti-colapso semântico, construção de nicho e destilação para Tsetlin.
- Dois arquivos de workflow Android CI.

Os arquivos **Arquitetura Mestre GEA em PDF**, **Completo.md** e **Links úteis e Referências** são mencionados em registros anteriores do projeto, mas não estavam presentes na Biblioteca acessível nesta auditoria. Não se presume que seus bytes ou o conteúdo integral estejam recuperados.

Os anexos são blueprints que se sobrepõem e contêm trechos de gerações diferentes. Os caminhos antigos não devem ser copiados por cima dos módulos atuais sem verificar contratos, serialização, determinismo e compatibilidade do diário.

## Correspondência com a branch atual

### Já existe uma implementação substancial

- **WorldCore e integridade:** `WorldActor` como escritor único, eventos canônicos encadeados por SHA-256, persistência SQLite WAL, auditoria, flashback e bifurcação.
- **Planejamento e cidade:** GOAP/Utility AI determinístico, expansão por lotes em espiral e WFC em seis arquétipos; o motor espacial removeu tetos antigos de ambientes e usa navegação A* e colisão círculo/AABB.
- **Cognição:** LTC/Hebbian nativo, proposta cognitiva tipada, Anokhin/aceitador de resultados, hormônios, PAD, GNW heurístico e sincronização de Kuramoto.
- **Memória e aprendizagem:** episódios observacionais 16D, reflexões, procedimentos validados, HNSW em memória, busca semântica local, herança de rotinas e memória por cidadão.
- **RSI limitado:** `RsiCoordinator`, `ShadowReplay`, CMA-ES e `PolicyRevision` com baseline, candidatos, janela independente de validação e promoção condicionada a Pareto.
- **Evolução social:** confiança recíproca, recombinação genética determinística, genealogia e herança de estado cognitivo.
- **Renderização/áudio:** Filament, malha urbana nativa, PBR, SSAO, sombras, pós-processamento, câmera orbital e AAudio procedural.
- **Voz neural:** motor Kokoro PT-BR opcional baseado em Sherpa-ONNX; o pacote de modelos é importado pelo usuário e não acompanha o APK.
- **Pesquisa/biblioteca:** ingestão SAF de TXT, Markdown, CSV, JSON e EPUB, curadoria Hugging Face, quarentena e proveniência.
- **Extensões:** registry ZIP de configuração e ponte Shizuku de telemetria somente de leitura.
- **Formato e distribuição:** codec `.grs` v1, filtros ABI `arm64-v8a`, `armeabi-v7a` e `x86_64`, CI com ASan/UBSan, testes, lint e artefatos APK separados.

Essas entregas não são equivalentes à implementação integral de todos os blueprints GEA/RSI. O comportamento em hardware físico, desempenho sustentado e compatibilidade entre fabricantes ainda exigem teste em aparelho.

### Integrações incrementais presentes nesta linha de trabalho

- **H2 — Memória/imunidade epistêmica:** seleção prioritária de episódios, retenção limitada a 512 episódios por habitante durante consolidação/overflow e operador de opinião subjetiva com deduplicação por hash do evento-fonte. Nesta atualização, a recuperação nativa passou a ter duas fases (cosseno/HNSW + reordenação por Q de MemRL), com crédito atrasado somente para episódios recuperados e persistência no evento cognitivo. O fusionador está disponível como motor tipado; ledger de crenças por domínio e propagação social completa ainda faltam.
- **H3 — Alostase:** classes térmicas, custo de deliberação dependente de calor/energia, gate para GGUF local e redução da contribuição da política neural sob carga térmica. Não há Tsetlin aprendido persistente ainda.
- **H4 — Anti-colapso:** entropia normalizada da distribuição de ações e bônus de novidade para ações raras quando a população se homogeneíza. É um proxy comportamental, não mede entropia lexical nem divergência Jensen–Shannon entre dialetos.

### Parcial ou ausente em relação aos originais

| Módulo original | Situação real |
|---|---|
| Quatro camadas de memória, nós associativos e Q-learning MemRL completo | Parcial: episódios/reflexões/procedimentos, consolidação ACT-R-style e Q de MemRL simplificado para reordenar memórias recuperadas estão integrados; grafo associativo completo, camadas semânticas/temporais/causais e ACT-R por nó ainda faltam. |
| Crenças subjetivas por domínio, desconto de confiança entre anéis de Dunbar e fusão persistida | Parcial: motor de opinião/deduplicação existe; ledger e propagação na rede social faltam. |
| BKT/IRT e repetição espaçada | Parcial: BKT por atividade, curva DSR e estimador IRT 3PL simplificado persistem em `MemoryBank.learning` e influenciam o escore cognitivo; FSRS-6/7 completo e calibração de itens IRT permanecem pendentes. |
| Cliodinâmica e instituições de Ostrom/Turchin | Pendente. |
| PIANO, active inference/expected free energy e calibração Brier | Parcial/pendente: decisões neurais e shadow replay existem; PIANO e gate Brier não. |
| Tsetlin proposicional treinado com aprovações LLM | Pendente. |
| Jogos de nomeação, negociação lexical, divergência fonética e dialetos localizados | Parcial: vocabulário por linhagem existe; naming games e evolução linguística completa não. |
| Coreografia facial/corporal, balões diegéticos e overlay de câmera | Pendente/parcial: habitantes renderizados e UI de diálogo existem; overlay diegético e coreografia da especificação não. |
| Rotinas completas de lounge, escrita física, compras e livro-razão de partidas dobradas | Parcial: ações existentes são mais simples e não implementam todas as affordances originais. |
| Dehancer/Kodak 70mm integral, SSR/SSGI/radiance cascades, IK, Earcut, Recast/Detour/RVO2 | Parcial ou pendente; Filament/PBR, pós-processamento, navegação A* e colisão já existem. |
| Voz espontânea multi-turno, Piper, herança vocal 256D e HRTF espacial | Pendente; Kokoro opcional e panorama estéreo não equivalem a esses itens. |
| Multi-GGUF residente, OpenCL/Vulkan/KleidiAI dinâmicos, LoRA treino, MeZO, TIES/DGM | Pendente; backend atual é CPU llama.cpp e LoRA é importação/aplicação, não treinamento online. |
| PDF, áudio/música, Parquet direto, Zstd integral do diário | Pendente; SAF suporta os cinco formatos declarados acima. |
| Persistência 24/7 com WifiLock/ADPF, governança completa 4/8/12 GB e Mesh LAN | Parcial/pendente: foreground service, WakeLock temporizado, memória/térmica e Shizuku somente leitura já existem. |
| Gestação, pets, regime comunal, vocações, ranking de 3 gerações e bônus de 15% | Pendente; genealogia determinística simples já existe. |
| Nicho ecológico persistente (trilhas, celeiros, escolas) e herança ecológica | Pendente. |

## Limites de distribuição e segurança

- O workflow é acionado em cada push e tenta produzir Debug e Release; os artefatos são publicados separadamente quando as etapas de build/staging passam.
- O Release atual usa chave de desenvolvimento do Gradle. Não é uma chave privada persistente de distribuição/Play; a troca deve ser feita com segredo externo fornecido pelo proprietário do repositório.
- Detectar um arquivo `libOpenCL.so`/`libvulkan.so` não comprova que o driver pode ser carregado nem que um backend está ativo.
- Android/OEM podem suspender serviços, rede e processos; WakeLock/foreground service não garantem sobrevivência universal 24/7.
- Não se devem remover os gates de testes para obter APK verde: artefato instalável e validação correta são resultados diferentes.

## Próxima ordem recomendada

1. Fechar a suíte de testes do núcleo, memória e expansão; manter artefatos APK separados em cada push.
2. Completar Tsetlin com estado persistido e evidência de aprovação, sem inferir reforço de falhas.
3. Persistir crenças por domínio e proveniência no journal, sem contar cópias de um mesmo evento como testemunhos independentes.
4. Implementar BKT e instituições/cliodinâmica com testes determinísticos.
5. Integrar nichos ecológicos e rotinas materiais ao `WorldActor` por nova versão de evento, preservando replay das versões antigas.
6. Avançar para áudio espacial/diálogos, parsers PDF/áudio/Parquet e backends nativos somente com fallback e teste por ABI.

**Critério de conclusão:** um módulo só deve ser marcado como concluído quando código, integração ao fluxo de estado/persistência, testes de regressão e evidência de build estiverem presentes.

## Incremento MemRL desta atualização

A implementação compacta está em `MemoryBank.kt`, `NativeMemory.kt` e `NativeMind.kt`. A utilidade Q é atualizada somente depois de um resultado de ação observado e somente para os IDs recuperados antes desse resultado. A consulta nativa mantém os candidatos por similaridade; Q altera a ordem dentro desse conjunto, sem substituir o filtro de relevância. Os campos novos vazios são omitidos na serialização para preservar eventos legados.

O algoritmo é inspirado em [MemRL](https://arxiv.org/abs/2601.03192), mas usa retorno imediato suavizado (`alpha=0.2)) e recompensa do domínio simulado. Não implementa o retorno Monte Carlo, sumarização de trajetória por LLM, ou benchmarks publicados pelo artigo. Ver também `docs/pesquisa-externa-2026-11-19.md`.

---

## Auditoria diferencial adicional — corpus consolidado Girassol_consolidado (1).txt

Fonte recuperada nesta execução: Girassol_consolidado (1).txt, corpus consolidado de 17 arquivos. O próprio cabeçalho informa que duplicatas exatas foram removidas e blocos de código com similaridade >=90% foram substituídos por referências; portanto este arquivo é um blueprint consolidado, não um pacote de fontes compiláveis completas. Ele inclui prompts históricos com HEADs e repositórios diferentes; prevalecem a branch e o repositório oficiais definidos pelo mantenedor nesta tarefa e o código atual validado.

### Correspondência do blueprint com o HEAD auditado (1ddda61d7c92)

| Processo do plano | Evidência no HEAD | Estado diferencial |
|---|---|---|
| 01 — Auditoria/crosswalk | WorldActor, RsiCoordinator, MemoryBank, NativeAutonomy, HardwareAutoTuner, MemoryPressureGuardian, ResearchCoordinator, WorldStore, CI e testes existentes | Auditoria atualizada; não equivale a concluir os demais processos. |
| 02 — Multi-ABI/backends/assinatura | Gradle declara arm64-v8a, armeabi-v7a, x86_64, APK universal e assinatura Release por variáveis de ambiente; CI separado compila/publica Debug e Release antes do emulador | Parcial: build Multi-ABI e assinatura por ambiente configurados; CMake ainda não ativa GGML_BACKEND_DL, GGML_CPU_ALL_VARIANTS, GGML_CPU_KLEIDIAI, GGML_VULKAN ou GGML_OPENCL. CI gera keystore efêmero se GIRASSOL_KEYSTORE_BASE64 não existir (assinatura válida para teste, sem continuidade de atualização); build/assinatura ainda aguardam CI verde. |
| 03 — Probe nativo Multi-SoC | HardwareAutoTuner.kt faz inventário Kotlin e verifica presença de arquivos de driver | Parcial: não existe soc_hardware_probe.hpp; presença de .so não comprova driver utilizável; sem afinidade Big/Prime integrada. |
| 04 — Serviço 24/7 | BiosphereService.kt, manifesto, MemoryPressureGuardian e telemetria Shizuku | Parcial: serviço/guardião existem, mas não equivalem a WifiLock low-latency, ADPF e garantia de sobrevivência OEM 24/7. Manter execução limitada e respeitar Doze/controle do sistema. |
| 05 — Cognição Anokhin/GNW | cognitive/anokhin_gnw_core.hpp, girassol_autonomy_jni.cpp, DualModelOrchestrator.kt | Parcial: núcleo heurístico e ponte existem; separação formal de monólogo/fala e GBNF integral precisam de testes de integração. |
| 06 — Tsetlin/GRST | Nenhum tsetlin_machine.hpp, girassol_gea_jni.cpp, GeaNativeBridge.kt ou tabela tsetlin_automata_blobs encontrado no tree auditado | Ausente. |
| 07 — Autotelic TTT | Nenhum autotelic_ttt_engine.hpp encontrado no tree auditado | Ausente. |
| 08 — Memória em quatro camadas/Jøsang | MemoryBank.kt, NativeMemory.kt, NativeMind.kt, MemRL simplificado e consolidação parcial documentada | Parcial; não existe SleepConsolidationAndImmuneEngine.kt nem grafo completo A-MEM/Sharp-Wave/Voyager com crenças persistidas por proveniência. |
| 09 — Active Inference/PIANO | WorldActor e decisões cognitivas tipadas; sem ActiveInferencePianoEngine.kt / PIANO / CoreSelf-PSelf | Parcial/ausente. |
| 10 — RSI delimitado | RsiCoordinator.kt, Shadow Replay e CMA-ES | Parcial; não existe BoundedRsiEngine.kt com Brier Score e rsi_promotion_ledger conforme o blueprint. |
| 11 — Pedagogia/genética diploide | BKT/DSR/IRT simplificados em MemoryBank; genealogia e recombinação determinística em WorldActor | Parcial; sem FSRS completo, diploidia com dominância alélica, Baldwin e Simbiogênese integrados. |
| 12 — Sociedade ADICO/cliodinâmica | Confiança social e relações no WorldActor | Parcial; sem SocietyAdicoCliodynamicsEngine.kt com instituições ADICO e PSI de Turchin. |
| 13 — Economia conservativa/estigmergia | Transferências de balanço e pré-condições de ações em WorldActor | Parcial; sem livro-razão de partidas dobradas completo, ReservationManager/leases e nicho ecológico persistente. |
| 14 — MAP-Elites/AURORA | CMA-ES nativo e testes associados | Ausente como MAP-Elites/AURORA; CMA-ES não é substituto equivalente. |
| 15 — Mundo 3D/navegação | Filament, malha urbana, A*, colisão espacial, PBR/SSAO/sombras e pós-processamento | Parcial; sem Earcut, Recast/Detour, RVO2/ORCA, IK e passes Hi-Z SSRT/SSGI/radiance cascades. |
| 16 — Voz neural/HRTF | Sherpa-ONNX/Kokoro opcional e NeuralSpeechEngine.kt; AAudio procedural | Parcial; sem diálogo espontâneo multi-party, Piper, herança vocal 256D e espacialização HRTF/ITD/ILD. |
| 17 — Dual-GGUF/LoRA/forja | llama.cpp CPU e orquestração de modelos; adaptação/importação não é treinamento | Ausente como treinamento LoRA real, dual-GGUF residente, MeZO/AdamW, TIES e ChildModelForge. |
| 18 — Expansão v2.1/UI | Compose UI, genealogia e páginas do app | Parcial; gestação, pets, pool comunal, ranking de gerações, bônus 15%, eras e flashbacks do blueprint não estão completos. |
| 19 — Ingestão/rede LAN | SAF TXT/MD/CSV/JSON/EPUB, pesquisa HF, swap arena e SQLite WAL | Parcial; PDF/áudio/Parquet, MeshSync LAN/CRDT e NonRootSwapArena completa não foram encontrados como módulos integrados. |
| 20 — Testes determinísticos | Suites Kotlin/JUnit, C++ ASan/UBSan e testes instrumentados existentes | Parcial: não há GeaDeterministicEnginesTest.kt nem cobertura específica para os módulos ausentes acima. |
| 21 — Push/PR/README | Branch oficial contém commits anteriores e README modificado; PR #1 permanece Draft | Parcial; não marcar Ready for Review enquanto os gates não terminarem e o checklist de escopo não estiver realmente concluído. |
| 22 — CI/artefatos | Workflow atualizado em `cc779ab5` com jobs `build-and-publish` e `instrumentation`; artefatos nomeados publicados no primeiro job | Pendente de validação: o novo workflow ainda precisa concluir ASan/UBSan, JUnit, Lint, builds Multi-ABI, assinatura e emulador 4 GiB; não presumir artifacts até o run publicar. |

### Incrementos de CI e assinatura após a auditoria inicial

- `61f2afb8` configura `signingConfigs.release` com as quatro variáveis de ambiente requeridas. Sem nenhuma delas, o build local usa a assinatura Debug; configuração parcial falha para evitar Release mal configurado.
- `cc779ab5` separa `build-and-publish` de `instrumentation`. O primeiro publica `Girassol-debug.apk` e `Girassol-release.apk` antes de o emulador iniciar; o segundo tem timeout de 25 minutos e desativa animações.
- A chave CI é persistente somente quando os Secrets de keystore e credenciais são fornecidos. Sem `GIRASSOL_KEYSTORE_BASE64`, a chave é efêmera: serve para validar assinatura/instalação, mas não para assinar atualizações futuras. A execução do workflow ainda é a evidência necessária.

### Decisões de integração e limites

- Preservar WorldActor como escritor determinístico único e versionar novos eventos/schemas antes de alterar estado persistido; não inserir chamadas JNI bloqueantes ou aleatoriedade não-semeada no tick.
- O blueprint contém código ilustrativo e dependências externas de grande porte. Cada módulo precisa de API definida, testes matemáticos/limites, integração e prova de build antes de ser considerado concluído; a mera criação de header não satisfaz o critério.
- Não usar sched_setaffinity para forçar continuamente threads em núcleos Prime nem manter WakeLocks/Wi-Fi locks indefinidos: Android/OEM controlam escalonamento e Doze, e essas práticas podem elevar temperatura e consumo. Preferir ADPF cooperativo, tarefas limitadas, foreground service apenas quando justificável e cancelamento sob pressão térmica/memória.
- dlopen bem-sucedido é somente uma verificação de disponibilidade de biblioteca. Um backend só pode ser habilitado após inicialização real, validação de dispositivos/queues e teste de inferência com fallback.
- Não gerar ou distribuir automaticamente uma chave privada com senha embutida no repositório. A chave de Release deve ser persistente e vir de GitHub Secrets; o APK assinado com debug serve apenas para testes internos.
- A especificação consolidada reúne alternativas e gerações de arquitetura. Recast/RVO2, HRTF, LoRA real, treinamento e execução de código mutável não devem ser simulados por wrappers vazios ou rotulados como concluídos sem implementação e testes.