# Sincronizando com o Traccar upstream

```bash
git remote add upstream https://github.com/traccar/traccar.git   # uma vez
git fetch upstream
git checkout -b chore/sync-upstream-AAAA-MM-DD master
git merge --no-ff upstream/master
```

## Arquivos com conflito recorrente

| Arquivo | Resolução usada |
|---|---|
| `README.md` | Manter a versão da fork |
| `src/main/java/org/traccar/web/WebServer.java` | Manter o upstream. A configuração do Jersey fica em `ApiResourceConfig` |
| `src/main/java/org/traccar/api/ApiResourceConfig.java` | Manter o upstream e registrar `SseFeature.class` |
| `src/main/java/org/traccar/session/ConnectionManager.java` | Manter o upstream e reaplicar: log de assinatura SSE, `globalListeners` (inclusive em `updateLog`, para os logs do administrador) e iteração sobre `List.copyOf(...)` |
| `src/main/java/org/traccar/api/security/SecurityRequestFilter.java` | Manter o upstream e reaplicar o bloco `@PublicFeature` |
| `src/main/java/org/traccar/ProcessingHandler.java` | Manter o upstream e reaplicar a medição de tempos (`ProcessingTimings`: `Times` no `QueuedPosition`, `receivedAt`, `handleTimed`, registros em `processEventHandlers` e `finishedProcessing`). Ver [processamento.md](processamento.md) |
| `src/main/java/org/traccar/model/LogRecord.java` | Manter o upstream e reaplicar o campo `time` (`getTime()`) |
| `src/main/java/org/traccar/config/Keys.java` | Manter as chaves `PUBLIC_*` e `SERVER_BUFFERING_LIVE_TOLERANCE` |
| `src/main/java/org/traccar/database/BufferingManager.java` | Manter o upstream e reaplicar o buffer adaptativo (`liveTolerance`, `lastReleased`, `isLive`, `markReleased`, contadores). Ver [processamento.md](processamento.md) |
| `src/main/java/org/traccar/handler/DistanceHandler.java` | Manter o upstream e reaplicar `isOutOfOrder`/`findPrevious` (construtor com `Storage` e `ProcessingTimings`) |
| `build.gradle` | Manter a dependência `jersey-media-sse` |
| `.gitmodules` | `traccar-web` aponta para `../gps-tracker-front.git`. Não aceitar a URL do upstream |

Depois do merge, rode:

```bash
./gradlew build      # compila, checkstyle e testes
```
