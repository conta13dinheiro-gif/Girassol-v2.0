<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:1bcdaa879214e113468868156f661b7fab3f504e1549176dfdfd4ffd83756e2f -->

<!-- coderabbit-ai-generated-section:v1:b6935071ff3f580e5a51ee49266220193be5a28b79a8e0553203c299ec681a3b -->

# Girassol v2.0

Simulação local para **Android 13+ (API 33)**, pacote `com.enhikyy.girassol`. Interface Jetpack Compose, apresentação Google Filament e componentes nativos C++/JNI. A versão de distribuição deste incremento é **0.9.0**. A arquitetura ARM64 não exige conta, rede ou modelo para a simulação local. Este incremento de física ainda não foi compilado ou executado, por orientação do usuário.

## Compilação manual

O workflow `.github/workflows/android.yml` executa somente por **Actions → Run workflow**. Gera Debug e Release ARM64; não instala APK, não inicia emulador e não executa testes. Nenhuma compilação foi acionada para esta alteração, conforme solicitado. O release não tem assinatura de distribuição configurada.

Toolchain: JDK17, SDK35, Build Tools35.0.0, NDK27.0.12077973, CMake3.22.1, Gradle8.11.1 e Kotlin2.1.10. O workflow baixa o matc oficial1.56.0 por SHA256 e compila todos os materiais antes do APK. Para compilação local futura:

```sh
FILAMENT_TOOLS_DIR=/caminho/filament/v1.56.0 ./gradlew :app:assembleDebug :app:assembleRelease
```

Materiais modificados exigem recompilação: os ativos binários antigos não correspondem às fontes0.9. O Gradle acusa a diferença se o matc não estiver configurado. Dependências Gradle e fonte llama.cpp por revisão/SHA256 são resolvidos na primeira compilação.

As suítes JVM/nativas/instrumentadas permanecem no código para execução futura pelo responsável. Não há confirmação de build, comportamento em aparelho ou60FPS para0.9.

## Experiência

- Mundo 3D em tela inteira, HUD compacto, pausa/1×/2×/4×, dock Biosfera/Cidadãos/Marcos/Sistema, modo cinema e mapa tático com camadas observacionais.
- Toque no habitante ou edifício para inspeção; arraste para orbitar, pinça para zoom e gesto de dois dedos para translação. A câmera tem amortecimento, inércia e acompanhamento de habitantes.
- Economia mostra saldos efetivos, contas de consumo/arquivo/estruturas e distribuição das atividades; não inventa séries históricas.
- Marcos surgem de construções, práticas comprovadas, primeira obra, crises, início da cognição, políticas promovidas e dias concluídos. Flashback carrega o estado integral verificado; bifurcação copia o prefixo do diário para um banco independente. Sistema permite voltar à linha original.

## Apresentação nativa

Filament1.56.0: materiais PBR procedurais para solo, pedra, madeira, metal, água e vegetação; vidro separado translúcido; normais analíticas, telhados, interiores e detalhes urbanos; habitantes articulados, acessórios e animação procedural. Ambiente com céu solar, estrelas/lua, irradiância difusa e mipmaps especulares GGX gerados em worker. Sombras direcionais DPCF com duas cascatas, SSAO, ACES, bloom, MSAA/FXAA.

A cena é renderizada fora da tela e recebe acabamento original com grão temporal, difusão quente de destaques e vinheta. Não inclui perfis proprietários Dehancer/Kodak nem comprovação de colorimetria Rec.2020. O clima visual deriva de semente/dia; não é ainda uma simulação física meteorológica. Meta de apresentação60FPS, reduzida sob calor; desempenho sustentado e aparência precisam ser medidos em ARM64 real.

Áudio AAudio sintetizado em C++: base60/120Hz, passos, divisões sub-harmônicas inspiradas no Trautonium, guia de onda com excitação de arco, vento e sinais de transformação. Clima visual, deslocamento, tensão social e marcos modulam a síntese. Callback sem locks ou alocações explícitas; controle serializado e parâmetros atômicos. O áudio começa desligado; ative em Sistema. Pausa, background e perda de foco suspendem a saída; desconectar fones desativa a preferência. Qualidade audível e roteamento exigem aparelho.

## Física espacial e locomoção — fontes0.9

A grade de navegação deriva dos mesmos triângulos de paredes, móveis, troncos e estruturas usados pela cena Filament. A* evita células ocupadas e diagonais que cortam cantos; movimentos curtos consultam o trajeto e aplicam circle-vs-AABB. Separação local impede aceitar posições ocupadas por outro cidadão. Estados históricos inicialmente sobrepostos recebem uma correção identificada no registro.

Cada ciclo usa30subpassos fixos de1/60s, gravidade−9,81m/s² e apoio amostrado nas superfícies do terreno/piso. O evento5 grava origem, semente, intenções, altitude, velocidade vertical e a polilinha completa. O WorldActor aplica esse resultado antes das ações e o SQLite persiste antes da publicação. Replay dos eventos1–4 mantém o caminho antigo; replay5 usa a trajetória gravada sem executar o solver.

A câmera e a apresentação seguem os pontos físicos, evitando interpolação reta através de quinas. A fase dos passos acumula distância efetivamente apresentada; sem deslocamento, a marcha para e resta a respiração do tronco. O pivô da sola é normalizado paraY0. Sombras de contato translúcidas por cidadão acompanham o piso e perdem densidade com a altura, somadas a SSAO e DPCF.

Limites atuais: navegação 2,5D no nível existente, colisões conservadoras AABB derivadas das faces, separação por steering/reserva local; não são Recast/Detour ou RVO2. Corredores muito congestionados podem exigir resolução de impasses mais completa. Não existe ainda inventário de objetos físicos soltos nem empilhamento dinâmico. O solver e os materiais novos aguardam compilação e validação pelo usuário.

## Aprendizagem e memória

Cada cidadão possui rede LTC12×8 nativa, oito valores de ação, plasticidade Hebbiana/Oja modulada pelo resultado e Big Five observacional. Pesos e estado neural são persistidos no evento, sem alterar o modelo GGUF. Novidade usa distância a comportamentos efetivamente observados. Exploração Gumbel usa fluxo derivado da semente, identidade e ciclo; a temperatura depende de curiosidade/novidade e a decisão resultante é registrada no evento. Isso amplia escolhas dentro do vocabulário atual, sem prometer invenção de instituições ou algoritmos novos. A personalidade é inicializada de forma reprodutível; herança estrutural NEAT e reprodução ainda não existem.

A memória persistida no checkpoint SQLite tem três representações:

1. Episódios com ciclo/evento, posição, resultado, emoção, importância e vetor observacional normalizado16D.
2. Reflexões estatísticas por atividade, destiladas durante o sono ou periodicamente. Esquecimento remove episódios antigos de baixa importância depois da consolidação.
3. Rotinas com três sucessos validados, reutilizadas na decisão e transmitidas por conversas próximas, com professor/evento de origem.

O HNSW nativo recupera candidatos por produto interno e ordena por similaridade, importância e recência. O índice é reconstruído em RAM a partir dos episódios persistidos; não é um segundo banco autoritativo. Os vetores descrevem atividade, posição, necessidades e resultado, **não são embeddings linguísticos**. As rotinas ainda correspondem às oito atividades existentes; não executam programas inventados como Voyager. O perfil mostra os registros e conexões recuperados, sem métricas fictícias.

## Hugging Face & Biblioteca

Pesquisa autônoma PT/EN, currículo de dez domínios, busca/curadoria no Hub, streaming de trechos, quarentena com duas avaliações GGUF, replay nativo e estudo físico na biblioteca. Diário comprimido encadeado por SHA256, embeddings GGUF completos e busca HNSW por modelo, cache textual direto JSON/JSONL/CSV com Range/mmap, cota/LRU/fixação e cancelamento por memória/temperatura. Controles em Sistema → Biblioteca.

Veja [arquitetura da biblioteca](docs/biblioteca.md) para contratos e limitações: treino LoRA e decoder direto Parquet continuam pendentes; avaliações do modelo não comprovam verdade factual.

## GGUF e LoRA

Em Sistema, importe um GGUF de instruções compatível e, opcionalmente, um adaptador LoRA compatível. Arquivos são copiados para armazenamento privado sem backup, identificados por SHA256 e indexados atomicamente. Importação cancelada preserva a seleção anterior. Não há teto artificial de512MB; espaço em disco, RAM disponível, arquitetura do processo e temperatura são verificados. A estimativa de RAM não garante suporte a todo modelo. Nenhum peso acompanha o APK.

Backend [llama.cpp b4514](https://github.com/ggml-org/llama.cpp/tree/ec7f3ac9ab33e46b136eb5ab6a76c4d81f57c7f1), CPU/duas threads, contexto512, resposta até64tokens, deadline cooperativo30s. GBNF limita a saída a uma atividade tipada; necessidades urgentes, alcance, capacidade e recursos continuam sendo revalidados pelo núcleo. O modo automático opcional alterna cidadãos a cada15s enquanto o app está aberto. Sem GGUF, a rede LTC e a simulação permanecem autônomas. Não há geração neural livre de diálogos, leis ou projetos urbanos nesta versão.

O LoRA é aplicado com `llama_adapter_lora_init`/`llama_set_adapter_lora`, usando o mesmo modelo base daquela inferência; é removido/liberado antes do contexto. Não há treinamento automático de adaptadores nem atualização online dos pesos do GGUF. Falha de compatibilidade é informada e preserva a simulação. O teste host cobre rejeição de adaptadores inválidos; aplicação positiva requer um adaptador treinado compatível ainda não verificado.

Teste GGUF host: `scripts/test-cognition.sh /caminho/modelo.gguf`. O smoke realizado usa SmolLM2-135M-Instruct Q4_K_M, revisão bartowski09816acd5d99df7be770d85ea30822623dab342c, SHA2562e8040ceae7815abe0dcb3540b9995eaa1fa0d2ca9e797d0a635ae4433c68c2d. Cards bartowski e HuggingFaceTB declaram Apache2.0; os pesos não são redistribuídos.

## RSI por Shadow-Replay

Descobertas e crises disparam um worker de baixa prioridade. O CMA-ES nativo adapta média, matriz de covariância completa e tamanho de passo de **oito vieses compartilhados de atividade**. A implementação C++ é própria, baseada no [tutorial de Hansen](https://arxiv.org/abs/1604.00772); não vincula libcmaes.

Cada candidato roda no WorldActor real com as redes nativas, em cópia isolada, durante256ticks. São duas gerações de dez candidatos. Promoção exige ganho composto superior a0,03 **sem regressão em nenhuma das quatro métricas** (novidade comportamental, resistência à escassez, convívio e eficiência energética observacional), tanto na janela de treino quanto numa segunda janela disjunta prevista pela referência. O limiar0,03 segue a hipóteseH1 mais estrita dos anexos; o exemplo alternativo0,025 não é usado.

Rejeitar todos os candidatos é um resultado válido. Pausa, troca de linha, destruição, calor ou pouca memória cancelam o experimento. Uma política aceita entra no próximo evento canônico, contendo pesos anteriores/novos, métricas, origem e método. O SQLite confere a origem contra o evento daquela linha. A validação descreve duas janelas de simulação; não prova superioridade universal nem sucesso futuro no mundo real. Ainda faltam políticas distritais, otimização estrutural e experimentos prolongados.

## Contratos preservados

WorldActor é a única autoridade lógica; percepção comum, arbitragem rotativa, revalidação e conservação de recursos permanecem. Serviço adota/publica a transição somente depois do commit SQLite WAL/FULL. Eventos1/2 preservam bytes e regras; evento3 registra a proposta GGUF; evento4 registra entradas cognitivas, pesos, memória e eventual promoção; evento5 acrescenta o resultado físico canônico. O replay usa os registros, sem executar o GGUF, o otimizador ou as redes nativas. Inferência e aprendizagem em hardwares distintos não são prometidas como bit a bit idênticas.

SQLite migra aditivamente até schema4: marcos, pesquisa e vetores, sem reescrever eventos/checkpoint anteriores. Auditoria confere IDs, ticks, hashes e vínculo ao checkpoint; não substitui assinatura autenticada contra reescrita integral do banco. Os diários completos são preservados; poda com âncoras criptográficas ainda não foi implementada.

## Estado e pendências

Este incremento **não conclui toda a especificação**. A cidade lógica ainda possui seis fundadores e construção até dez ambientes. Permanecem: navegação de multidões Recast/Detour e planejamento GPGOAP; distritos WFC/Earcut e crescimento urbano aberto; reprodução/morte/genealogia e mutação estrutural NEAT; instituições/leis/comércio emergentes completos; cognição linguística generativa e treinamento LoRA; compactação com poda auditável; direção cinematográfica automática; Shizuku/modo dedicado, DGM/runtime de código, OpenCL e dois GGUF residentes, treino contínuo real; dimensionamento por hardware8/12GB; validação visual, de áudio, memória e60FPS.

As bibliotecas ALIEN, MultiNEAT, EvolutionNet, GPGOAP, Recast, sqlite-vec, WFC, Earcut e libcmaes foram referências pesquisadas; não são apresentadas como integradas. Código efetivamente incorporado: Filament, llama.cpp, tinymind LTC, hnswlib, OkHttp e RapidJSON, com versões/licenças preservadas.

## Organização

- `research/`: currículo, protocolos HF/HTTP, aquisição, quarentena e cota sem Android.
- `core/`: estado, contratos, diário/replay, memória e critérios de promoção, sem Android.
- `app/src/main/cpp/`: malhas, projeção, LTC/Hebbian, HNSW, CMA-ES, llama.cpp/JNI e síntese/AAudio.
- `app/src/main/java/`: serviço, armazenamento, importação, coordenação cognitiva, render e Compose.
- `app/src/main/materials/`: fontes dos materiais PBR, vidro, contato e acabamento. Recompile com matc56: `FILAMENT_TOOLS_DIR=/caminho/filament/v1.56.0 scripts/compile-materials.sh`. A build verifica hashes dos fontes/ativos.
- `docs/arquitetura.md`: referências, compatibilidade e limites de validação.


<!-- /coderabbit-ai-generated:v1 -->