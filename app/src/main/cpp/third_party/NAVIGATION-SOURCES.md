<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:e033f5efb8f972756b5541a6f3c34a35d1b6c3186141f1d1e91921c3018f28dd -->

# Fontes de navegação vendorizadas

- RecastNavigation/Detour: tag oficial `v1.6.0`, commit `6dc1667f580357e8a2154c28b7867bea7e8ad3a7`. Diretórios Recast e Detour copiados da fonte oficial; `Detour/Source/DetourNavMesh.cpp` tem uma alteração local marcada: destruição segura após falha de inicialização e inicialização dos tiles antes da segunda alocação; licença preservada em `recastnavigation/License.txt`. Arquivo oficial `https://codeload.github.com/recastnavigation/recastnavigation/tar.gz/refs/tags/v1.6.0`, SHA-256 `f565cc91b85df95a656cfc672e41c02e8aa44ba2363905aa8277ce20ea87491d`.
- RVO2: tag oficial `v2.0.2`, commit `c96a6ec8328492bae0721e59774026a119a60b4a`. `Vector2.h` e `Definitions.h` copiados sem alterações, licença Apache 2.0 preservada em `rvo2/LICENSE`. Arquivo oficial `https://codeload.github.com/snape/RVO2/tar.gz/refs/tags/v2.0.2`, SHA-256 `5fc7fde809a412324bafe0280d66886bba0c6922509f9483d95ad6e5488aac43`.
- `../navigation/orca.cpp` adapta Agent.cpp/linearProgram1–3 com atribuição original e arrays fixos; `recast_nav.cpp` implementa o produtor/consumidor da arena e do tile no Girassol. As adaptações são código do projeto e não substituem a licença dos autores originais.


<!-- /coderabbit-ai-generated:v1 -->