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

- **H2 — Memória/imunidade epistêmica:** seleção prioritária de episódios, retenção limitada a 512 episódios por habitante durante consolidação/overflow e operador de opinião subjetiva com deduplicação por hash do evento-fonte. O fusionador está disponível como motor tipado; a persistência de crenças por domínio e a propagação social completa ainda faltam.
- **H3 — Alostase:** classes térmicas, custo de deliberação dependente de calor/energia, gate para GGUF local e redução da contribuição da política neural sob carga térmica. Não há Tsetlin aprendido persistente ainda.
- **H4 — Anti-colapso:** entropia normalizada da distribuição de ações e bônus de novidade para ações raras quando a população se homogeneíza. É um proxy comportamental, não mede entropia lexical nem divergência Jensen–Shannon entre dialetos.

### Parcial ou ausente em relação aos originais

| Módulo original | Situação real |
|---|---|
| Quatro camadas de memória, nós associativos e Q-learning MemRL completo | Parcial: memória episódica/reflexões/procedimentos existem; grafo semântico completo e acesso ACT-R por nó não. |
| Crenças subjetivas por domínio, desconto de confiança entre anéis de Dunbar e fusão persistida | Parcial: motor de opinião/deduplicação existe; ledger e propagação na rede social faltam. |
| BKT pedagógico e cliodinâmica/instituições de Ostrom/Turchin | Pendente. |
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