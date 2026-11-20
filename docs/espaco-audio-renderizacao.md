<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:b3e7772f44a3002a2dd79f6e6e39e4ab4c944d4cfe649cd5f9da0506ead0371f -->

<!-- coderabbit-ai-generated-section:v1:52d2c02259bb5a23f4da92317655832bdb0befe6585fbb876d2939074ceb6d24 -->

<!-- coderabbit-ai-generated-section:v1:250d75b1b4b9eb0e89806796ddd46174171730dd97a5baf90782be8f8839efa1 -->

# Frente 10 — navegação, áudio e gráficos

## Fluxos efetivamente conectados

`NativeSpatial.advance` envia posição e velocidade horizontal anterior para `Scene::step`. A cena deriva da geometria real os pisos de suporte e as projeções dos obstáculos da física, voxeliza essa malha física com Recast, aplica erosão pelo raio físico de 0,18 m e usa A* de polígonos/straight path do Detour. Os obstáculos conservam a projeção XZ da colisão existente, extrudida a 2 m para impedir que móveis baixos se tornem superfícies caminháveis. Quando o ponto de interação cai numa ilha isolada dentro de um móvel, uma busca nos polígonos alcançáveis escolhe a projeção mais próxima no componente do agente; a atividade só é admitida dentro da sala real. Cada um dos 30 subpassos de 1/60 s calcula ORCA a partir de um snapshot comum; os agentes parados também reagem. A varredura contra paredes e a integração vertical continuam como última barreira física. As novas rotas podem diferir do solver anterior; replay de eventos existentes continua consumindo o `SpatialFrame` gravado, sem recalcular a física.

RecastNavigation 1.6.0 e Detour vêm do commit `6dc1667f580357e8a2154c28b7867bea7e8ad3a7`; ORCA/LP1–3 foi adaptado do RVO2 2.0.2 para armazenamento fixo. Não há dependência de download desses motores no build.

A fala real do Sherpa ONNX 2.0.1 passa por `NativeSpatialAudio`, em blocos de até 2048 frames. A HRTF analítica usa atraso fracionário Woodworth (cabeça 0,0875 m, som 343 m/s), ILD e filtro contralateral. Um raio na malha real mede superfícies interceptadas, deduplicadas a 4 cm, para atenuação/filtro de oclusão. Fonte: habitante selecionado; ouvinte: câmera 3D. A posição é capturada no início de cada fala. O painel de prévia usa azimute de teste, sem fonte no mundo. Não se trata de uma HRTF medida, simulação de reflexões ou rastreamento contínuo do ouvinte durante a fala.

Enquanto `AudioTrack` reproduz a fala, uma flag atômica reduz somente trilha/comum do Trautonium para ganho 0,25; ataque 25 ms e retorno 250 ms. Passos permanecem audíveis. Não há alocação, lock ou JNI dentro do callback AAudio.

Filament 1.56.0 tenta Vulkan quando Android anuncia suporte de hardware. Falha Java de criação, inicialização, superfície ou frame libera a geração de recursos, invalida callbacks antigos e reconstrói cena/materiais/swapchain em OpenGL ES. Uma segunda falha fecha a viewport e informa o erro. Falhas fatais internas de driver/processo não são capturáveis por esse mecanismo. O pós-processamento e os materiais reais são recriados em ambos os backends.

## Limites de memória e falhas

- Máximo 150 agentes e 192 salas por cena física/acústica antes de alocar entradas; a API compartilhada de malhas preserva o teste histórico de 256 salas e passa a rejeitar 257 ou mais antes da alocação. O contrato anterior não possuía teto; essa limitação explícita atende ao orçamento solicitado e pode rejeitar uma visualização antiga acima de 256 salas sem alterar seus eventos/replay; o mundo atual admite até 128 anexos além das salas iniciais.
- Arena Recast/Detour de 32 MiB por thread física ativa, reutilizada por operação; tile persistente até 2 MiB; até 270000 células, 8192 polígonos/nós e 512 pontos de corredor. Entradas de índices de colisão têm teto agregado de 8 milhões (32 MiB), além da malha/células limitadas pelo produtor. O limite por thread exige considerar o número de workers no orçamento total.
- O destrutor e a inicialização de tiles em `DetourNavMesh.cpp` possuem uma correção local identificada para desfazer inicialização parcial com segurança. Alocadores de terceiros lançam `std::bad_alloc` antes de retornar um endereço nulo a placement new do Recast. A ponte traduz a falha para `OutOfMemoryError`; outros erros C++ tornam-se exceções Java. O teste injeta falhas em cada posição de alocação da construção e da consulta do cenário de referência. A geometria navegável usa o mesmo contrato de suporte físico: terrenos e superfícies até 0,26 m; móveis e telhados continuam como obstáculos rasterizados. A resolução inicial de 0,05 m, ajustada pelo teto de células, preserva os corredores estreitos da biblioteca.
- ORCA usa arrays fixos de até 150 restrições. HRTF mantém estado 536 bytes (wire 544), entrada 8192 e saída 16384 bytes, sem heap C++ por bloco; Kotlin mantém também 4096 floats de saída reutilizados. Ray de oclusão guarda até 256 interseções.
- Sherpa: texto até 240 caracteres, áudio até 90 s, callback interrompe geração excedente e valida tamanho final, pacote até 410 MiB, guarda de 1200 MiB livres, 2 threads. A biblioteca ONNX mantém seus próprios tensores internos: estes limites não demonstram seu pico de RAM no aparelho.
- Worker de voz: uma tarefa ativa e uma pendente. Worker gráfico: uma ativa e até 256 pendentes com contrapressão; buffers Filament configurados em 12/4 MiB e arenas de render/handles em 8/8 MiB. Esses limites não medem a memória total do driver/GPU.

## Evidência e limites de aceitação

Os resultados executados e os hashes são registrados em `progresso-implementacao-girassol.md`. Os testes nativos cobrem corredor com obstáculo/erosão, ORCA recíproco, falhas de arena, ITD independente, simetria/ILD/filtro, invariância por blocos, NaN sem mutação, oclusão triangular e ducking do sintetizador real. O host chama JNI e os consumidores reais; o replay canônico deve permanecer equivalente.

O teste Android de renderização contém recuperação de uma falha de superfície em aparelhos Vulkan e recriação de recursos reais em ES. Compilar esse teste não demonstra sua execução. Sem alvo Android conectado, continuam pendentes apresentação Vulkan/ES, audição Sherpa/AAudio, pico de RAM no Moto G62 e carga 24/7. Não há alegação de conclusão desses requisitos de dispositivo.


<!-- /coderabbit-ai-generated:v1 -->