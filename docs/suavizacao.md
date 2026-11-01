# Suavização de localização de ônibus por crowdsourcing

Oct 31, 2026 · @Júnior

## Visão geral

O backend calcula a posição suavizada. O cliente só anima o marcador entre dois snapshots. A posição do ônibus não é o GPS de um passageiro: é a mediana ponderada dos passageiros a bordo, projetada no trajeto e filtrada ao longo do tempo.

O ciclo roda a cada 2 s (`TICK_MS`) e tem cinco passos:

1. **Celular envia fix** com lat, lng, accuracy, speed e timestamp. Intervalo mínimo entre fixes de um usuário: 900 ms.
2. **Filtro e projeção por usuário**: descarta fix ruim, projeta no trajeto e converte em `s` (metros ao longo da rota). Velocidade do usuário passa por EMA.
3. **Eleição**: extrapola cada voto para "agora", pondera, agrupa por proximidade e tira a mediana ponderada de cada grupo.
4. **Track do ônibus**: associa cada grupo eleito a um ônibus existente, aplica regras físicas (sem ré, velocidade máxima) e outra EMA de velocidade.
5. **Snapshot e animação**: converte `s` em lat/lng sobre a rota, envia ao cliente, que interpola o marcador em 1,4 s.

A ideia central é trabalhar em uma dimensão (`s`) em vez de lat/lng. Ruído lateral some na projeção; o ônibus fica sempre sobre a linha; velocidade, ré e extrapolação viram contas simples sobre um número.

Código de referência no projeto circular-ufpa: `src/engine/matcher.ts`, `src/engine/election.ts`, `src/engine/engine.ts`, `web/src/map.ts`.

## Etapa 1: filtro e projeção por usuário

Cada fix vira um número `s` (metros ao longo do trajeto) ou é descartado. Ordem dos testes:

1. **Frequência**: fix a menos de 900 ms do anterior é ignorado.
2. **Precisão**: `accuracy <= 0` ou `> 50 m` é descartado. Após 30 s seguidos assim, usuário fica em estado `low_accuracy`.
3. **Corredor**: projeta o ponto em todos os segmentos do trajeto. Só aceita candidatos a até `corridorM + min(accuracy, corridorM)` da linha (30 m + até 30 m). Ponto isolado fora do corredor é só descartado; 15 s fora rebaixa o usuário ("desceu"); 30 s fora zera a cinemática.
4. **Escolha do candidato** (o trajeto pode passar duas vezes pela mesma rua):
   - Com histórico recente (≤ 90 s): escolhe o candidato de menor custo `|avanço − esperado| × 0.5 + distânciaLateral × 2`, onde `esperado = speed × dt`. Avanço negativo além de 25 m ou maior que `maxSpeed × dt + 40` é impossível: zera a cinemática e recomeça.
   - Sem histórico: filtra por rumo (GPS ou deslocamento, tolerância 60°), depois por ônibus já rastreado a menos de 120 m, depois o mais próximo. Se o segundo estiver a menos de 12 m de diferença, marca `ambiguous` (voto vale metade).
5. **Velocidade** ao longo do trajeto, com EMA α = 0,5:

```latex
v_{inst} = \frac{\Delta s}{\Delta t}, \quad v_{gps} = speed \cdot \cos(heading_{gps} - bearing_{rota})
```

```latex
v_{along} = 0.5\,v_{inst} + 0.5\,v_{gps}, \quad speed \leftarrow 0.5\,speed + 0.5\,\mathrm{clamp}(v_{along}, -3, v_{max})
```

Sem `v_gps` (GPS sem heading), usa só `v_inst`. GPS com speed abaixo de 0,5 m/s conta como parado.

6. **Está no ônibus?** Só quem está `riding` vota. Vira `riding` após uma "arrancada" (média ≥ 13 km/h em janela de 15–60 s, duas vezes seguidas, ou GPS ≥ 13 km/h no sentido da via em duas leituras) e avanço ≥ 50 m nos últimos 90 s. Perde `riding` se ficar parado mais de 300 s, ou 240 s sem arrancada andando devagar (a pé).

Pseudocódigo:

```
function ingestFix(user, fix):
  if fix.t - user.lastFixAt < 900: return
  if fix.accuracy > 50: return
  cands = route.projectAll(fix.latlng, maxDist = 30 + min(fix.accuracy, 30))
  if cands.empty: markOffRoute(user); return
  chosen = user.hasRecentHistory ? minCost(cands) : chooseByHeadingOrNearest(cands)
  dt = max(0.5, (fix.t - user.sAt) / 1000)
  vInst = advance(user.s, chosen.s) / dt
  vAlong = gpsAlong != null ? 0.5*vInst + 0.5*gpsAlong : vInst
  user.speed = 0.5*user.speed + 0.5*clamp(vAlong, -3, vMax)
  user.s = chosen.s; user.sAt = fix.t; user.accuracy = fix.accuracy
  updateRiding(user)
```

## Etapa 2: eleição da posição

A cada tick, a posição de cada ônibus é a mediana ponderada dos votos dos passageiros, não a média. Mediana resiste a GPS ruim e a quem envia posição falsa.

**Quem vota**: usuário `riding`, condição `ok`, com `s` definido e último fix aceito há no máximo 30 s.

**Extrapolação para "agora"** (no máximo 10 s de avanço):

```latex
s_{voto} = s + \mathrm{clamp}(speed, 0, v_{max}) \cdot \min(idade, 10)
```

**Peso do voto**:

```latex
w = trust \cdot boost \cdot e^{-idade/20} \cdot (0.3 + 0.7\,accScore) \cdot (0.5 \text{ se ambíguo, senão } 1)
```

- `trust`: reputação do usuário (sobe quando concorda com o consenso). Motorista recebe peso fixo maior (`DRIVER_VOTE_WEIGHT`, padrão 3).
- `accScore = clamp(1 − (accuracy − 5) / 45, 0, 1)`: 1 para GPS ≤ 5 m, 0 para ≥ 50 m.
- `e^(−idade/20)`: voto de 20 s vale 37% de um voto novo.

**Agrupamento** (`clusterVotes`): ordena votos por `s`; uma lacuna maior que 150 m separa ônibus diferentes. Em rota circular, o corte começa no maior intervalo vazio, para não partir um ônibus que está passando pelo ponto inicial.

**Eleição do grupo** (`elect`):

1. Mediana ponderada `m` das posições.
2. Votos a mais de 70 m de `m` viram dissidentes.
3. Recalcula `m` só com quem concorda (uma vez).
4. Velocidade do grupo = mediana ponderada das velocidades de quem concorda.
5. `support` = soma dos pesos de quem concorda. Confiança = `1 − e^(−support/0.7)`.

```
function weightedMedian(items):   // items: {v, w}
  sorted = items.sortBy(v)
  total = sum(max(0, w))
  acc = 0
  for x in sorted:
    acc += max(0, x.w)
    if acc >= total / 2: return x.v
```

Em rota circular, as posições do grupo são convertidas para distâncias relativas ao primeiro voto antes da mediana, para não quebrar na volta do zero.

## Etapa 3: track do ônibus

O track é o estado persistente de cada ônibus entre ticks. A posição eleita só entra no track depois de passar por regras físicas.

**Associação grupo → track**: para cada grupo eleito (maior support primeiro), prevê onde cada track estaria agora e escolhe o de menor custo.

```
for e in elections (por support desc):
  for tr in tracks da rota (ainda livres):
    dt   = min(60, agora - tr.updatedAt)
    pred = tr.s + max(0, tr.speed) * dt
    d    = delta(pred, e.s)
    if d < -150 or d > 250 + 15*dt: continue      // longe demais
    score = |d| - 150 * (membrosEmComum / membros)  // mesmos passageiros = mesmo ônibus
  melhor score -> updateTrack(tr, e)
  nenhum -> novo track (até BUS_COUNT ônibus)
```

Grupo sem track formado só por quem acabou de descer de um ônibus que segue visível longe dali é descartado como "fantasma".

**Atualização** (`updateTrack`):

1. `d = delta(tr.s, e.s)`. Recuo entre 0 e −40 m vira 0: ônibus não anda de ré, isso é ruído.
2. Velocidade instantânea `inst = clamp(d / dt, 0, vmax)`, com `dt ≥ 0,5 s`.
3. Alvo: se o grupo informa velocidade > 0,3 m/s, `alvo = 0.5 × e.speed + 0.5 × inst`; senão `alvo = inst`.
4. EMA: `tr.speed = 0.5 × tr.speed + 0.5 × clamp(alvo, 0, vmax)`.
5. `tr.s = tr.s + d` (na primeira vez, `tr.s = e.s`).
6. Heading vem da geometria da rota em `s`, não do GPS. Evita seta girando com ruído.
7. Status: `lost` se o fix mais novo tem mais de 60 s; track expira após 600 s.

No snapshot, `s` vira lat/lng com `route.latLngAt(s)`. O cliente recebe ponto já sobre a linha.

## Etapa 4: animação no cliente

O cliente web não filtra nada: só desliza o marcador da posição atual até a nova em 1400 ms, com ease-out cúbico. Fonte: `web/src/map.ts`.

```ts
// ao receber snapshot, se o alvo mudou:
bm.from = bm.marker.getLatLng(); // posição atual (mesmo no meio de outra animação)
bm.to = target;
bm.t0 = performance.now();

// a cada frame:
const t = Math.min(1, (now - bm.t0) / 1400);
const e = 1 - (1 - t) ** 3; // ease-out cúbico
marker.setLatLng([
  from.lat + (to.lat - from.lat) * e,
  from.lng + (to.lng - from.lng) * e,
]);
if (t < 1) requestAnimationFrame(step);
```

Se o usuário prefere movimento reduzido (`prefers-reduced-motion`), o marcador pula direto ao alvo. O app mobile (Compose) não anima: desenha lat/lng do snapshot direto.

Limitações desta animação:

- **Para e anda**: anima 1,4 s e fica parado \~0,6 s até o próximo snapshot (tick de 2 s). Não há previsão.
- **Corta curvas**: interpolação em linha reta entre lat/lng; numa esquina o marcador sai da rua.

## Parâmetros e como portar

Valores padrão em `src/config.ts` e constantes de `matcher.ts`. Ajuste conforme o veículo e a cidade.

| Parâmetro | Padrão | Papel |
| --- | --- | --- |
| `TICK_MS` | 2000 ms | Intervalo de cálculo e envio de snapshot |
| `MAX_ACCURACY_M` | 50 m | Fix pior que isso é descartado |
| `CORRIDOR_M` | 30 m | Distância máxima da linha (+ até 30 m da accuracy) |
| `MAX_SPEED_KMH` | 80 km/h | Teto de velocidade e de salto aceitável |
| `BUS_MIN_SPEED_KMH` | 13 km/h | Velocidade de "arrancada" que prova estar no ônibus |
| `MAX_DWELL_S` | 300 s | Parado mais que isso = desceu |
| `VOTE_MAX_AGE_S` | 30 s | Voto mais velho não conta |
| `CLUSTER_GAP_M` | 150 m | Lacuna que separa dois ônibus |
| `DISSENT_M` | 70 m | Distância da mediana que vira dissidente |
| `MIN_CONTRIBUTORS` | 1 | Mínimo de votos para existir ônibus |
| `TRACK_LOST_S` | 60 s | Sem fix novo = status `lost` |
| `TRACK_EXPIRE_S` | 600 s | Track removido |
| Intervalo mínimo de fix | 900 ms | Anti-flood por usuário |
| Tolerância de ré | 25 m (usuário), 40 m (track) | Recuo menor é ruído |
| EMA de velocidade | α = 0,5 | Usuário e track |
| Meia-vida do peso | e^(−idade/20) | Voto de 20 s vale 37% |
| Extrapolação máxima | 10 s | Avanço do voto até "agora" |
| Animação | 1400 ms, ease-out cúbico | Só no cliente web |

**Pré-requisito**: o outro app precisa conhecer o trajeto como polilinha e saber projetar um ponto nela (`s` e distância lateral) e fazer o inverso (`latLngAt(s)`, `bearingAt(s)`). Sem rota fixa, troque a projeção por um filtro de Kalman 2D.

**Melhorias recomendadas ao portar:**

1. **Interpolar sobre o trajeto**: anime `s` em vez de lat/lng e converta cada frame com `latLngAt(s)`. O marcador segue a rua nas curvas. Exige mandar `s` no snapshot.
2. **Dead reckoning no cliente**: depois de chegar ao alvo, continue avançando `s += speed × dt` até o próximo snapshot, com limite (ex.: 10 s). Remove o "para e anda".
3. **Duração da animação = intervalo do tick** (2 s), com easing linear quando em movimento contínuo.
4. **Correção suave**: se a previsão do cliente errar, corrija em 0,5–1 s em vez de saltar; salte só se o erro passar de \~200 m.
5. **Mesma lógica no mobile**: hoje o app Compose não anima; use `Animatable` sobre `s`.
