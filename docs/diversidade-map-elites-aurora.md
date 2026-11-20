<!-- coderabbit-ai-generated:v1 -->

# Arquivo de diversidade comportamental

## Produtor, qualidade e consumo

A cada observação com aprendizado novo, resultado OK e job físico DONE com recibo do tick atual, NativeMind submete uma amostra. Movimento, reservas e falhas não recebem rótulo artificial de sucesso. O vetor de trajetória usa as frequências das oito atividades nos últimos 32 episódios, cinco necessidades atuais, coordenadas x/z normalizadas à extensão de 48 metros e `(tanh(recompensa/0,2)+1)/2`. Esses descritores estruturados não são embeddings de linguagem.

O eixo explícito é a fração de episódios TALK/BUILD/WRITE, uma aproximação operacional de interação/contribuição comum. A qualidade é `0,5*média(necessidades) + 0,3*tanh(recompensa/0,2) + 0,2`, limitada a [-1,1]; o termo final corresponde ao resultado físico bem sucedido. Não é uma medida empírica de bem-estar. <!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:35862918a96dcded5e594296345b6c753c1e264fd008a41845fb6f7c66e7ace5 -->

A política arquivada contém os oito valores de ação do readout neural C++ registrados na decisão anterior, divididos por oito e limitados pelo contrato de pesos/ativação a [-8,8]. O resultado físico avalia esse módulo anterior; pesos recém-atualizados pelo feedback não recebem qualidade retroativa. Um evento legado sem esses valores não produz amostra. Não representa cópia de genoma RSI nem promoção de política verificada.

<!-- /coderabbit-ai-generated:v1 -->

Um nicho ocupado é amostrado uniformemente pelo PRNG canônico (seed, agente, tick e domínio qd-donor-v1). Seus valores contribuem com fator 0,015 para a competição PIANO, portanto no máximo 0,12 por ação. A consulta usa o arquivo anterior à observação atual, evitando lookahead. PSelf, Tsetlin, normas, pré-condições físicas e os gates RSI continuam atuantes. O arquivo preserva os melhores módulos observados; esta frente não introduz mutação genética nem o efeito Baldwin.

## Encoder e arquivo 3D

A média usa EMA 0,01. O primeiro componente aplica Oja com taxa 0,015. O segundo usa o resíduo deflacionado no primeiro componente atualizado, Oja e dois passos de Gram-Schmidt. Ambos são normalizados; degenerescência recebe eixo determinístico ortogonal. Projetam-se os dois componentes centrados pela sigmoide. Os três eixos alimentam uma grade 8×8×8 (512 nichos), com fronteiras 0/1 limitadas aos bins 0/7.

Após atualizar a PCA com o lote, todos os elites retidos são reprojetados a partir da trajetória bruta e reatribuídos. Colisões conservam maior qualidade e, no empate, menor hash de proprietário; empates do mesmo proprietário conservam o anterior. O encoder adaptativo é aproximado e a reatribuição pode reduzir cobertura. Cobertura = ocupados/512, exibida separadamente do avanço das onze frentes.

## Limites, JNI e persistência

- Arrays nativos fixos: 16 dimensões, oito valores por política, 512 células e até 150 candidatos por lote; zero alocação no heap C++ no caminho.
- Estado C++: 131.480 bytes no host verificado. Asserção estática limita estado + reconstrução + lote a menos de 300.000 bytes de pilha; a compilação de cada ABI confere seu próprio tamanho.
- Wire little-endian versionado, 125.852 bytes. Pool direto Kotlin por thread: esse estado mais 33.600 bytes de entrada; vetor zero auxiliar de 125.852 bytes. Armazenamento canônico esparso: cabeçalho de 412 bytes e 247 bytes por nicho ocupado, codificados em hexadecimal com tamanho máximo explícito. Estes limites não comprovam RAM total no telefone.
- ABI GeaNativeBridge 3 inclui qdUpdate. A ponte confere capacidades exatas, leitura/escrita, limites, lote completo, finitude, ortogonalidade, projeções, contagem e tick monotônico antes de alterar a saída. Não há handle global ou arquivo compartilhado entre live/shadow.
- Arquivo imutável pertence ao mundo e entra no CognitiveFrame, evento e checkpoint SQLite, na transação existente. Defaults ausentes mantêm os hashes históricos. A integridade criptográfica do evento/checkpoint é a autoridade sobre bytes válidos alterados; validação estrutural sozinha não autentica uma política.
- Histórico de novidade retido em 256 descritores por agente. Contador cumulativo separado conserva o significado de descobertas do RSI ao podar; migração inicializa o contador pelo tamanho legado, sem reescrever eventos anteriores.

A equivalência live/shadow, testes de corrupção, recuperação binária, ortogonalidade e consumo devem ser executados sobre a revisão publicada. Métricas de RAM/latência e carga prolongada requerem o Moto G62; testes instrumentados exigem um alvo Android disponível.
