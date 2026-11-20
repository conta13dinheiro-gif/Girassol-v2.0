<!-- coderabbit-ai-generated:v1 -->

# Pedagogia versionada: FSRS 4.5, IRT e habilidades verificadas

## Compatibilidade

O contrato histórico está em `aprendizagem-bkt-dsr-irt.md`. `SkillLearningTrace.observe` continua usando os parâmetros e a heurística originais quando não há estado FSRS. Campos novos são omitidos quando vazios. Eventos antigos continuam sendo reproduzidos a partir do frame gravado, sem recalcular cognição. `CURRENT_RULES_VERSION` permanece 8.

Novos frames de `NativeMind` ativam `pedagogyEnabled`. O objeto `FsrsReview.modelVersion=45` identifica o novo modelo. Migrar um traço antigo preserva P(L), dificuldade, estabilidade, habilidade e quantidade de revisões; o contador de execuções comprovadas começa em zero. A migração não inventa avaliações individuais nem lapsos anteriores.

## FSRS

A implementação usa o modelo de memória FSRS 4.5 e os 17 pesos publicados em [ts-fsrs v3.5.7, revisão fixa](https://github.com/open-spaced-repetition/ts-fsrs/tree/0fdaa11f74fc236546cdcb8d48cb678b053b397b/src/fsrs). Essa versão preserva a curva obrigatória:

`R(t,S)=(1+(19/81)*t/S)^(-0.5)` e `I(r,S)=S*(r^(-2)-1)/(19/81)`.

Inicialização das quatro notas, reversão da dificuldade à média, estabilidade após recordação com penalidade Hard/bônus Easy e estabilidade após esquecimento seguem o modelo 4.5. Uma unidade de tempo equivale a um tick de simulação. O cálculo mantém Double sem arredondamento decimal intermediário, não aplica fuzz de calendário e limita o intervalo a pelo menos um tick. Dificuldade fica em [1,10], estabilidade em [.1,1e9]; revisões e lapsos têm saturação inteira. Não é uma alegação de implementação de FSRS 6/7 ou de parâmetros otimizados para usuários humanos.

Os resultados reais fornecem as notas operacionais: falha explícita → Again; sucesso com recompensa <.03 → Hard; recompensa >=.25 → Easy; demais sucessos → Good. O contrato anterior de considerar PRECONDITION_FAILED e RESERVED como observações incorretas permanece. Movimento e duplicação de episódio não produzem revisão. Essas notas descrevem desempenho do agente; não são autoavaliações humanas. A recuperabilidade e a informação IRT entram no incentivo de prática; a previsão também participa da verossimilhança da inferência ativa, com peso .25 e gate Tsetlin .75.

## BKT e IRT

No caminho novo, BKT usa slip=.08, guess=.15 e transição `.10+.18*aptidão`. O proxy de aptidão é o locus de curiosidade já expresso por dominância; nenhum alelo ou regra de segregação foi acrescentado. A probabilidade latente BKT é atualizada por Bayes sem aplicar o esquecimento FSRS duas vezes. Recuperabilidade permanece separada.

`IrtItem` implementa `p=c+(1-c)*sigmoid(a*(theta-b))`, com c=0 no 2PL. Estudo/conversa usam 3PL; demais atividades usam 2PL. Derivadas analíticas de theta/a/b/c e informação Fisher dirigem atualização online MAP com priors fixos, taxa decrescente e limites rígidos. O banco tem no máximo oito itens por agente. Priors ancoram a escala que seria não identificada em calibração conjunta livre. Isso é calibração operacional regularizada por resultados da simulação, sem alegação de validade psicométrica externa. Falhas de recurso/ocupação podem influenciar o modelo, conforme o contrato de observação preservado.

## Biblioteca e execução

A biblioteca contém até oito programas tipados por agente. Um programa é destilado somente após pelo menos três trabalhos físicos concluídos, com P(L)>=.75. Cada evidência guarda tick, hash canônico do estado, job e sala; os três comprovantes mais recentes ficam limitados por atividade. Ensinar exige tanto domínio quanto três execuções locais do professor. O aprendiz recebe o programa e a autoria, sem receber contagens de execução. Sua própria estimativa de domínio deve satisfazer a pré-condição antes de usar o programa.

A linguagem é finita: RESERVE → MOVE_TO → ACQUIRE_TOOL (quando necessário) → EXECUTE_WORK → DEPOSIT_LEDGER. Os programas são receitas verificadas para as oito atividades disponíveis; não executam texto arbitrário ou código gerado sem verificação. Os operadores dirigem efeitos reais: lease com TTL/renovação, chegada pela física JNI, aquisição de martelo/estilete com madeira real, trabalho validado pelo WorldActor, desgaste/reciclagem da ferramenta, sinal espacial e recibo. Se o agente ainda não possui programa elegível, a execução intrínseca original continua disponível para adquirir experiência.

As pré-condições cobrem domínio, hidratação/energia mínimas, recursos, ferramenta, subsídio commons e parceiro quando necessários. São verificadas na seleção e na chegada. O executor mantém contador de programa; estados incompatíveis são rejeitados pelos invariantes. Normas ADICO e necessidades vitais continuam anteriores à execução.

## Persistência e limites

Traços completos, parâmetros IRT e programas fazem parte do frame/evento/checkpoint SQLite. `bkt_fsrs_mastery` recebe a projeção de P(L), D, S, theta e revisões; parâmetros adicionais permanecem no payload canônico. `voyager_skill_library` recebe programa, pré-condições, domínio, autoria, contagens e comprovantes. O produtor verifica os eventos fonte e grava tudo na mesma transação. A auditoria estrita cobre novos eventos ativados; migrações históricas não fabricam linhas antigas ausentes. Bifurcações preservam o prefixo e seus comprovantes.

Limites: 150 agentes, oito itens/programas por agente, três comprovantes por atividade, até cinco operadores por programa e nenhum novo buffer nativo. As medições no Moto G62 e de operação 24/7 continuam sendo requisitos de aceite independentes.
