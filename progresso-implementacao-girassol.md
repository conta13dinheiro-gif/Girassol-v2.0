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
