# Aprendizagem BKT, DSR e IRT no núcleo cognitivo

## Contrato

Cada cidadão mantém, dentro do próprio `MindState.memory`, no máximo um `SkillLearningTrace` para cada uma das oito atividades do mundo. O registro é serializado no mesmo estado cognitivo canônico; não cria banco paralelo nem altera o envelope histórico dos eventos quando a coleção está vazia.

Uma observação só é contabilizada quando `MemoryBank.observe` cria um episódio novo a partir de uma ação confirmada pelo `WorldActor`. Sucesso corresponde a uma memória de resultado criada pela ação; falhas explícitas (`PRECONDITION_FAILED` e `RESERVED`) entram como observações incorretas. Movimento, propostas rejeitadas e ticks sem resultado não são avaliações.

## BKT

Parâmetros iniciais: (P(L_0)=0.20), (P(T)=0.08), (P(G)=0.20), (P(S)=0.10). A probabilidade anterior é reduzida pela recuperabilidade DSR desde a última revisão. Para resposta correta:

[
P(Lmid c)=\frac{P(L)(1-P(S))}{P(L)(1-P(S))+(1-P(L))P(G)}
]

Para resposta incorreta:

[
P(Lmid w)=\frac{P(L)P(S)}{P(L)P(S)+(1-P(L))(1-P(G))}
]

Depois da observação, aplica-se a transição de aprendizagem (P(L')=P(Lmid o)+(1-P(Lmid o))P(T)).

## DSR e revisão espaçada

A implementação usa a curva solicitada no prompt mestre:

[
R(t,S)=\left(1+\frac{19}{81}\frac{t}{S}\right)^{-0.5}
]

(S) é estabilidade em ticks da simulação. Para uma meta de recuperabilidade (r), o intervalo é calculado invertendo a curva:

[
I(r,S)=\frac{S}{19/81}(r^{-2}-1)
]

Assim, (R(S,S)=0.9), e a agenda é expressa na unidade de tempo determinística do mundo, não em dias de calendário Android. A atualização de estabilidade/dificuldade é uma heurística pequena e testada (aumenta após sucesso, retrai após falha); não é uma implementação completa do algoritmo FSRS-6 ou FSRS-7. A versão FSRS completa exige parâmetros e regras adicionais e permanece pendente.

## IRT 3PL

A previsão de acerto utiliza discriminação (a=1), chute (c=0.20) e dificuldade derivada da escala (D\in[1,10]):

[
P(certo\mid\theta,a,b,c)=c+\frac{1-c}{1+e^{-a(\theta-b)}}
]

A habilidade latente (	heta\in[-4,4]) recebe atualização online limitada pela derivada da verossimilhança Bernoulli. Como o Girassol ainda não possui um banco de itens calibrado, (a) e (c) são fixos e (b) deriva da dificuldade do perfil; isso é IRT operacional simplificado, não calibração psicométrica de itens.

## Integração e limites

- `NativeMind.evaluate` adiciona um bônus pequeno, limitado e contínuo para praticar habilidades pouco dominadas ou cuja revisão está vencida. O planejador GOAP e as verificações de pré-condição do `WorldActor` continuam soberanos.
- A coleção fica limitada a oito registros, um por atividade; os valores, timestamps e intervalos são validados na fronteira do `MemoryBank`.
- A ausência do campo novo é aceita ao desserializar estados antigos. O default vazio é omitido pela serialização canônica para preservar hashes históricos.
- Os testes de JVM cobrem atualização correta/incorreta, curva DSR, inversão do intervalo, monotonicidade IRT, atribuição única por evento, migração de JSON antigo e persistência.
- Isto não completa as quatro camadas Working/Episodic/Semantic/Procedural, nem substitui o treinamento LoRA ou uma implementação FSRS-6/7.

