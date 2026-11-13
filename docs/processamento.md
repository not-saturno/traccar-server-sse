# Tempo de processamento das posições

Por que a posição aparece alguns segundos depois do log da mensagem, e como medir onde está o atraso.

## Caminho de uma mensagem

1. O rastreador envia a mensagem. `StandardLoggingHandler` lê e o decoder do protocolo gera a `Position`
   (`serverTime` = agora).
2. O log sai na hora pelo SSE (`ConnectionManager.updateLog`), com `LogRecord.time`.
3. `ProcessingHandler` recebe a posição e a entrega ao `BufferingManager`.
4. **Buffer de reordenação** (adaptativo nesta fork, ver abaixo): posições ao vivo passam direto; posições
   históricas ou fora de ordem ficam retidas `server.buffering.threshold` (padrão do Traccar: 3000 ms) e são
   reordenadas por horário (`fixTime`).
5. **Fila do dispositivo**: uma posição só começa depois que a anterior do mesmo aparelho terminou.
6. Handlers de posição, um depois do outro (filtro, distância, geocercas, endereço, banco etc.), e os handlers de
   evento.
7. **Pós-processamento** (`PostProcessHandler`): atualiza o cache e chama `ConnectionManager.updatePosition`. A
   posição sai pelo SSE/WebSocket e aparece no mapa.

## Medição antes da otimização (2026-11-13, máquina de desenvolvimento, H2, protocolo `watch`)

Com o buffer original, que retinha toda posição:

| Etapa | Média recente |
|---|---|
| Buffer de reordenação | **3,03 s** |
| Fila do dispositivo | 0,12 ms |
| Todos os handlers de posição juntos | ~3 ms (o maior: `DatabaseHandler`, ~1,5 ms) |
| Todos os handlers de evento juntos | ~1,5 ms |
| Pós-processamento | ~4 ms |
| **Total** | **3,04 s** |

O processamento em si leva milissegundos: quase todo o atraso era o buffer.

## Otimização: buffer adaptativo + distância fora de ordem

### Buffer adaptativo (`BufferingManager`)

Cada posição é classificada ao chegar:

- **Ao vivo**: o horário do GPS (`fixTime`) está a até `server.buffering.liveTolerance` (padrão 10000 ms) do horário
  do servidor e não é mais antigo que a última posição liberada do aparelho. É liberada **na hora**.
- **Retida**: o resto (posição histórica, como o `UD2` do watch, que traz dados guardados sem sinal; posição fora de
  ordem; heartbeat sem fix, com horário de 1980). Espera `server.buffering.threshold` e é reordenada com as outras
  retidas, como no Traccar original.

A posição ao vivo não espera atrás das retidas: senão um heartbeat sem fix retido seguraria todas as seguintes, em
cadeia (aconteceu no primeiro teste). Uma retida liberada depois de uma mais nova é tratada como fora de ordem
(item abaixo).

`server.buffering.liveTolerance` = `0` volta ao comportamento original (retém tudo). Rastreador com relógio
errado (mais de 10 s de diferença) tem as posições retidas; o painel mostra isso nos contadores.

### Distância de posição fora de ordem (`DistanceHandler`)

O `DistanceHandler` original mede a distância contra a última posição do cache, que é a mais nova. Uma posição que
chega fora de ordem ficava com `distance` e `totalDistance` calculados a partir de um ponto depois dela (o cache e
as posições seguintes não são afetados, porque o `PostProcessHandler` só atualiza o cache com posições mais novas).
Agora, quando a posição é mais antiga que a do cache, a distância é medida a partir da **posição gravada
imediatamente anterior por `fixTime`** (uma consulta ao banco, só nesse caso). Sem posição anterior, `distance` = 0
e `totalDistance` = 0. Em erro de banco, mantém o comportamento original.

O mapa nunca volta para trás: posição mais antiga que a última é gravada (aparece nos relatórios), mas não
atualiza a última posição nem vai aos streams. Isso já era assim no Traccar.

### Resultado (mesmo cenário)

| | Antes | Depois |
|---|---|---|
| Posição ao vivo, da mensagem até sair no stream | ~3 s | milissegundos (sai junto com o log) |
| Posição histórica (`UD2`, 90 s atrás) | ~3 s, distância contra a posição mais nova | ~3 s, distância contra a anterior por horário |
| Contadores do teste | | 12 ao vivo, 2 retidas, 1 fora de ordem corrigida |

## Como medir no servidor

Endpoint desta fork, só administrador:

- `GET /api/processing/timings`: `since` (início da medição), `window` (amostras por etapa usadas na média recente
  e no p95, 500), `bufferingThresholdMs` e `liveToleranceMs` (configuração do buffer), `counters` (`live`:
  liberadas na hora; `buffered`: retidas; `outOfOrder`: distância medida da anterior por horário) e `steps`, na
  ordem do pipeline.
  Cada etapa tem `name`, `kind` (`STAGE`, `POSITION_HANDLER`, `EVENT_HANDLER`), `count`, `averageMs`,
  `recentAverageMs`, `p95Ms`, `maxMs` e `lastMs`.
- `DELETE /api/processing/timings`: zera a medição.

Etapas (`STAGE`): `buffer`, `queue`, `postProcess` e `total` (da chegada no `ProcessingHandler` até a posição sair).
Os handlers aparecem pelo nome da classe; handlers desligados na configuração não aparecem.

Os dados ficam só em memória e recomeçam quando o servidor reinicia. O custo é um `System.nanoTime()` por etapa.
A tela **Logs** mostra esse painel para o administrador (botão **Desempenho**).

```bash
curl -s -u admin@exemplo:senha https://SERVIDOR/api/processing/timings | jq '.steps[] | {name, recentAverageMs, p95Ms}'
```

## Implementação

| Arquivo | Papel |
|---|---|
| `src/main/java/org/traccar/timing/ProcessingTimings.java` | Coletor em memória (`@Singleton`): contagem, média, máximo e janela das últimas 500 amostras por etapa |
| `src/main/java/org/traccar/ProcessingHandler.java` | Mede o buffer, a fila, cada handler (da chamada até o callback, porque alguns são assíncronos), o pós-processamento e o total |
| `src/main/java/org/traccar/api/resource/ProcessingResource.java` | `GET`/`DELETE /api/processing/timings` |
| `src/main/java/org/traccar/database/BufferingManager.java` | Buffer adaptativo (`isLive`, `lastReleased`) e contadores |
| `src/main/java/org/traccar/handler/DistanceHandler.java` | Distância de posição fora de ordem a partir da anterior por horário |
| `src/main/java/org/traccar/config/Keys.java` | `server.buffering.liveTolerance` |
| `src/test/java/org/traccar/database/BufferingManagerTest.java`, `DistanceHandlerTest.java` | Testes |
| `traccar-web/src/components/ProcessingTimings.vue` | Painel na tela de Logs |
