<!-- coderabbit-ai-generated:v1 -->

# Biblioteca autônoma do Girassol

## Fluxo implementado

1. Impasses reais de execução ou baixa novidade criam uma intenção de pesquisa por habitante. O currículo SQLite contém dez domínios, pré-requisitos acíclicos e domínio acumulado. As consultas PT/EN são determinísticas.
2. A busca pública do Hub avalia card, licença explícita, modalidade textual e tarefas. Dados privados, gated, sem licença reconhecida ou de mídia sem texto são excluídos. Não se executam scripts de datasets.
3. Datasets Server fornece splits e lotes de até100registros por `/search`, com fallback `/rows`. Metadados usam ETag;429/5xx recebem espera exponencial; leituras/retries conferem o guardião. O fallback textual compacto usa árvore da revisão imutável do Hub, HTTP Range/If-Range, arquivo privado e renomeação atômica. JSON, JSONL e CSV são decodificados em C++/RapidJSON sobre `mmap` somente leitura. `NonRootSwapArena` é um mapeamento de arquivo, **não swap do Android nem RAM adicional**.
4. O lote selecionado recebe SHA256, envelope com origem/licença/revisão/tick/hash anterior, compressão Deflate e transação WAL. Downloads diretos também preservam no lote o hash do arquivo completo. Triggers impedem atualização/remoção do diário de aquisição. A auditoria confere compressão, hashes, cadeia e evento da linha.
5. Os dados entram em quarentena. Dois cidadãos distintos, alternando papéis de pesquisador e cético, usam o mesmo GGUF sequencialmente. GBNF permite apenas veredito/confiança. O segundo papel não recebe o primeiro veredito. Sem GGUF, a quarentena permanece; a simulação continua com LTC determinístico.
6. Duas avaliações favoráveis com confiança>=90 permitem um experimento sobre256ticks do WorldActor e redes reais. A comparação considera novidade, resistência à escassez, convívio e eficiência energética; qualquer regressão recusa o aprendizado. Isso avalia o efeito na simulação, **não valida cientificamente a verdade do texto**. Dois papéis do mesmo modelo não são fontes independentes.
7. O pesquisador dirige-se à biblioteca por uma intenção STUDY, preservando a prioridade das necessidades urgentes. A exposição ao conteúdo ajusta a rede LTC/Hebbiana nativa. Identidades de lote/trecho/modelo, revisores, origem e métricas entram no evento canônico; checkpoint e marcação de assimilação são atômicos. O estímulo atual é derivado do conteúdo e influencia valores das oito ações; não transforma texto arbitrário em novas regras físicas ou programas executáveis.
8. Embeddings normalizados de dimensão integral são extraídos do GGUF e persistidos como BLOB SQLite, vinculados ao hash do modelo/adaptador. O HNSW nativo consulta shards limitados em memória, mesclando top-k. Vetores de modelos diferentes nunca são comparados. Similaridade0,62 é um limiar operacional da pesquisa, sem alegação de calibração universal de embeddings de modelos generativos.
9. Marcos de aprendizado entram na história. Replay de eventos incorporados funciona sem rede/modelo. Fork copia o prefixo histórico de aquisições e vetores; avaliações posteriores são retiradas da cópia. O mundo de origem permanece preservado.

## Controles e recursos

Sistema → Biblioteca oferece auto-pesquisa, Wi-Fi, revisão cruzada automática, cotas256/512MB e1/2/4GB, feed com origem/motivação/PT/EN, inspeção de trechos/revisores/hashes, fixação e pausa de estudo. Desativar revisão mantém a quarentena. Parar estudo preserva o diário e o que já foi aprendido.

O cache direto usa LRU e respeita fixação; o diário imutável conta na cota e não é removido para abrir espaço. Ao atingir a cota, a aquisição pausa. `MemoryPressureGuardian` exige MemAvailable>1100MiB, ausência de lowMemory e temperatura abaixo de MODERATE. Rede, inferência e decodificação possuem cancelamento cooperativo. Revisão e busca local podem continuar offline quando houver recursos; downloads obedecem a Wi-Fi/conectividade. O terminal da biblioteca recebe uma luz pulsante ciano enquanto o worker está ativo.

O contexto de revisão/embeddings possui2048tokens; o protocolo de atividade anterior permanece512. Lotes HTTP são limitados a2MiB, arquivos diretos a150MiB e campos de estudo têm janela de32KiB. Estes são orçamentos explícitos de memória; trechos incompletos não são ensinados como completos. Registros maiores que a janela efetiva de revisão permanecem em quarentena.

## Compatibilidade

SQLite evolui aditivamente até schema4: schema2 adiciona marcos,3 adiciona pesquisa,4 adiciona vetores. Eventos antigos1/2/3/4 sem campos novos mantêm sua serialização. O WorldActor continua sendo a autoridade; trabalho de rede/inferência retorna à thread proprietária e não publica pesos antes do commit.

O APK de produção é ARM64; x86_64 é mantido para testes de emulador. Android mínimo33. O release gerado precisa da chave de distribuição; o debug tem assinatura de desenvolvimento.

## Ainda não implementado

- Decodificação direta Parquet em NDK e descoberta pelo endpoint `/parquet`; Parquet acessível por Datasets Server usa o caminho de linhas do servidor.
- Ajuste fino incremental real de adaptadores LoRA e consumo de um buffer de destilação. O carregamento de adaptadores compatíveis existe; não há switch que simule um treino inexistente.
- Criação de novas habilidades executáveis/leis físicas pelo texto, instituições e ensino intergeracional reprodutivo completo.
- Planejamento GPGOAP e navegação Recast/Detour: o deslocamento lógico existente permanece.
- Certificação de verdade das fontes, desempenho sustentado60FPS e maratona em ARM64 físico de4GB.

## Dependências e validação

RapidJSON297637bc0a0f00dc504dbc1a47ebdaa577f8ae19; arquivoSHA256104f57d1512da80740a3eca7d7b2acee7f749d79dbf15328ad1869c5c962011b. Cabeçalhos originais MIT e msinttypes BSD preservados; JSON_checker não foi incorporado. OkHttp4.12.0; HNSW e llama.cpp mantêm as revisões já fixadas no projeto.

O workflow `.github/workflows/android.yml` executa testes JVM de currículo/HTTP/cache/quarentena/cota/pressão/retomada, ASan/UBSan nativo incluindo parser e índice semântico, GGUF real (gramática e embeddings), lint, APK debug/release, integração JNI e instrumentação API33. Resultados são os da execução vinculada ao commit; código ou teste adicionado não constitui, por si só, evidência de aprovação.
