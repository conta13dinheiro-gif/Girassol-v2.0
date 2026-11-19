# Pesquisa técnica externa — Girassol / GEA
Data: 2026-11-19

## Escopo e método

Pesquisa em seis frentes: memória/aprendizagem; dinâmica social/instituições; evolução; IA local Android; navegação/renderização/áudio; confiabilidade térmica e execução prolongada. Foram usados Exa e Context.dev e inspecionados os repositórios/documentos primários abaixo. A consulta do Context.dev foi configurada com 30 idiomas (en, pt, es, fr, de, it, nl, sv, no, da, fi, pl, cs, sk, hu, ro, bg, el, ru, uk, tr, ar, he, hi, bn, zh, ja, ko, id, vi); os resultados de maior sinal recuperados concentraram-se em inglês. Isso não prova ausência de implementações em outros idiomas.

Os projetos externos são referências, não dependências automaticamente aprovadas. Antes de incorporar código, revisar licença, versão, dependências, ABI, tamanho, manutenção e compatibilidade com o diário canônico do Girassol.

## 1. Memória episódica e aprendizagem por utilidade

Fontes: [MemRL — artigo arXiv:2601.03192](https://arxiv.org/abs/2601.03192); [MemTensor/MemRL — implementação oficial, MIT](https://github.com/MemTensor/MemRL).

MemRL separa a política de raciocínio estável da memória episódica plástica. A recuperação ocorre em duas fases: (A) candidatos por similaridade; (B) reordenação por utilidade Q. Cada memória é estruturada como intenção, experiência e utilidade, que se atualiza a partir do retorno observado. A implementação oficial é Python e depende de configuração de modelos externos; não é uma dependência Android pronta.

### Implementação realizada nesta branch

- Cada episódio continua ligado ao evento canônico que o originou.
- MemoryUtility persiste Q, contagem de recuperações e ticks de recuperação/feedback dentro de MemoryBank.
- HNSW/cosseno nativo seleciona até oito candidatos; a fase B usa 0.75 × similaridade + 0.25 × (Q/5).
- As memórias recuperadas ficam pendentes para atribuição de crédito. Quando chega o próximo resultado de ação registrado, somente os episódios realmente recuperados recebem Q ← Q + 0.2 × (r − Q).
- Utilidade e crédito pendente são serializados junto ao estado cognitivo. Campos vazios são omitidos para preservar a serialização/hash dos estados legados.
- Similaridade cosseno [-1,1] é convertida em peso [0,1] antes da média de recompensa, evitando denominador nulo/negativo.

**Limite:** é uma adaptação compacta de MemRL para o vocabulário determinístico do Girassol, não uma reprodução integral do artigo. Não há resumo de trajetórias por LLM, retorno Monte Carlo multi-etapas, embeddings textuais universais ou validação nos benchmarks do artigo. Q representa utilidade observada no simulador, não verdade factual nem habilidade geral.

## 2. Tsetlin Machine e reflexos explicáveis

Fontes: [cair/TsetlinMachine — MIT](https://github.com/cair/TsetlinMachine); [artigo de referência](https://arxiv.org/abs/1804.01508); [Weighted Tsetlin Machine em C++](https://github.com/adrianphoulady/weighted-tsetlin-machine-cpp).

A Tsetlin Machine representa padrões como cláusulas proposicionais de literais booleanos. Cada autômato decide incluir/excluir um literal. Feedback Tipo I reforça padrões frequentes; Tipo II melhora discriminação. A máquina aprende online e produz regras interpretáveis. A implementação de referência principal é Python/Cython; não deve ser empacotada diretamente no app Android.

**Próxima integração:** motor Kotlin/C++ compacto com estado inteiro dos autômatos, PRNG determinístico e serialização explícita. Treinar somente com rótulos originados de resultados verificados do WorldActor; nunca transformar aprovação de LLM em verdade. Separar treino e validação, controlar desbalanceamento e medir precisão, recall, Brier e falso reflexo. O motor Tsetlin continua pendente.

## 3. Aprendizagem pedagógica e espaçamento

Fontes: [CAHLR/pyBKT — MIT](https://github.com/CAHLR/pyBKT); [Introdução a BKT, Psych 2023](https://www.mdpi.com/2624-8611/5/3/50); [explicação técnica FSRS-6](https://expertium.github.io/Algorithm.html); [Free Spaced Repetition Scheduler](https://github.com/open-spaced-repetition/free-spaced-repetition-scheduler).

BKT estima domínio latente por habilidade a partir de sequências de acertos/erros, com parâmetros de conhecimento inicial, aprendizagem, chute e deslize. Para observação correta:
P(K|correct) = P(K)(1-slip) / [P(K)(1-slip) + (1-P(K))guess].
Depois da observação:
P(K_next) = P(K|obs) + [1-P(K|obs)]learn.
A observação incorreta usa slip e probabilidade de não-chute. BKT exige sequências por habilidade e calibração; não deve inferir domínio a partir de uma única interação.

FSRS-6 é um agendador de revisão baseado em estabilidade/dificuldade e histórico de revisões. É complementar a BKT, não substituto: BKT estima domínio; FSRS agenda revisão.

**Próxima integração:** persistir domínio por habilidade/cidadão, com parâmetros versionados e incerteza. Alimentar BKT apenas com tarefas explícitas de estudo/avaliação. Usar FSRS para revisar lições aceitas, sem alterar leis ou fatos do mundo. Ambos pendentes.

## 4. Opinião, redes sociais e instituições

Fontes: [defSim — influência social, GPL-3.0](https://github.com/defSim/defSim); [manynet — DeGroot, Hegselmann–Krause e Deffuant](https://stocnet.github.io/manynet/reference/make_opinion.html); [survey de dinâmica de opiniões](https://arxiv.org/abs/2004.05286); [instituições ADICO e ação coletiva](https://thecommonsjournal.org/articles/10.18352/ijc.606); [Cliodynamics.jl](https://github.com/hyperpolymath/Cliodynamics.jl).

Deffuant–Weisbuch atualiza um par de opiniões apenas quando a distância está dentro de um limiar de confiança: x_i' = x_i + μ(x_j − x_i) e x_j' = x_j + μ(x_i − x_j). Friedkin–Johnsen acrescenta persistência de opinião inicial: x(t+1) = ΛW x(t) + (I−Λ)x(0). São modelos diferentes.

ADICO descreve instituições por Atributo, Deôntico, Aim/ação, Condição e Or else/sanção. Estudos de commons mostram regras emergindo por tentativa, imitação e ação coletiva. Cliodynamics modela tendências lentas como pressão demográfica, sobreprodução de elites, capacidade estatal e instabilidade; não é preditor determinista de eventos históricos.

**Próxima integração:** persistir crenças por domínio e proveniência; executar influência somente entre relações válidas, respeitando confiança e anéis sociais. Modelar instituições como regras estruturadas e testáveis, com custos, sanções e conservação de recursos. Separar escala rápida de conversas da escala lenta institucional. Não copiar defSim para o APK sem avaliar GPL-3.0; portar matemática ou escolher implementação compatível. Módulos pendentes.

## 5. Evolução aberta e diversidade

Fontes: [pyribs — Quality Diversity/MAP-Elites](https://docs.pyribs.org/en/stable/); [Leniabreeder — MAP-Elites e AURORA em Lenia](https://leniabreeder.github.io/).

MAP-Elites mantém um arquivo de soluções de alta qualidade distribuídas por descritores comportamentais, em vez de selecionar apenas um máximo escalar. AURORA aprende descritores de diversidade sem exigir que todos sejam definidos manualmente. pyribs separa arquivo, emissores e scheduler.

**Próxima integração:** arquivo limitado e determinístico de nichos comportamentais (cooperação, diversidade cultural, eficiência de recursos, resiliência e inovação), com orçamento explícito de CPU/RAM. Avaliar por replay isolado e validação fora da janela de treino. Não introduzir autoalteração irrestrita de código. MAP-Elites/AURORA pendentes.

## 6. Navegação, multidões e áudio espacial

Fontes: [Recast Navigation/Detour — zlib](https://github.com/recastnavigation/recastnavigation); [RVO2/ORCA — Apache-2.0](https://gamma.cs.unc.edu/RVO2/); [3D Tune-In Toolkit — espacialização binaural](https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0211899).

Recast gera navmeshes; Detour consulta rotas globais e suporta navmesh em tiles, adequado à expansão incremental. RVO2/ORCA resolve desvio local de colisões; não substitui o planejador global. HRTF usa filtragem/convolução binaural e requer dados apropriados.

**Próxima integração:** Detour como planejador global e RVO2 como evitador local, sem transferir autoridade de estado ao renderer. Construir/atualizar tiles em lotes, com orçamento de memória e teste de determinismo. HRTF opcional, fallback estéreo e teste de latência. Módulos pendentes.

## 7. IA local e controle térmico Android

Fontes: [llama.cpp — documentação Android](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md); [ExecuTorch + KleidiAI em dispositivos móveis](https://pytorch.org/blog/unleashing-ai-mobile/); [LiteRT/ML Drift para GPU](https://developers.googleblog.com/ml-drift-next-gen-gpu-aiml-inference-at-the-edge); [Android Thermal Mitigation](https://source.android.com/docs/core/power/thermal-mitigation).

llama.cpp oferece rota CPU/ARM64 com otimizações ARM; KleidiAI acelera kernels compatíveis, conforme arquitetura/compilação. ExecuTorch e LiteRT são runtimes alternativos, não aceleradores universais de GGUF. LiteRT ML Drift usa caminhos GPU suportados e fallback CPU, mas requer modelo/runtime compatíveis. A presença de biblioteca OpenCL/Vulkan no filesystem não prova que o backend funciona.

Android expõe níveis térmicos por PowerManager; o app deve reduzir trabalho em estados moderados/severos e nunca desabilitar mitigação térmica do sistema.

**Próxima integração:** manter llama.cpp como backend atual e introduzir backends opcionais após teste real de capacidade, ABI e modelo. Benchmark obrigatório: carregamento, TTFT, tokens/s, pico de RAM, energia por token, temperatura e estabilidade. Usar PowerManager/ADPF para governança adaptativa e não prometer 24/7, pois Android/OEM pode suspender ou encerrar processos.

## 8. Decisões de integração

1. Preservar WorldActor como autoridade única e o diário/event replay como contrato.
2. Toda nova memória, crença, regra social e política deve ser dado tipado, versionado e persistido; nada de texto livre executável.
3. Algoritmos novos começam como motores puros determinísticos, com testes de invariantes e replay antes da integração ao app.
4. Teste em ARM64 real é obrigatório; build em CI prova compilação, não desempenho sustentado, autonomia ou estabilidade 24/7.
5. Não adicionar dependência apenas por popularidade: revisar licença e fazer benchmark comparativo.
6. Critério de conclusão: Planned → Implemented → Integrated → Validated. Só Validated conta como concluído.
