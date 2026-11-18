# Emissões, consumo e custo (BFF) e histórico dos fatores

Os relatórios da tela **Relatórios** do front são calculados no servidor e entregues prontos para desenhar
(back for front). O front não baixa mais posições: antes, o comparativo anual pedia `GET /api/positions` do ano
inteiro de cada dispositivo e fazia as contas no navegador, com constantes fixas no código. Agora a resposta tem
só as linhas agregadas (12 meses por dispositivo, ou uma linha por hora).

Os parâmetros do cálculo (fatores de emissão, preços, rendimento) deixaram de ser constantes: têm histórico por
ano, mês ou dia, e cada dia do relatório usa o valor que vale naquele dia.

## Cálculo

### Distância

1. As posições do dispositivo no intervalo são lidas em ordem de `fixTime` (só as colunas `fixTime`, `latitude`,
   `longitude`, `attributes`, em stream), mais a última posição antes do intervalo.
2. O aumento de `totalDistance` entre duas posições seguidas vai para a **hora** da posição mais nova. Queda de
   `totalDistance` (hodômetro zerado) é ignorada; posição sem o atributo é pulada.
3. Hora com mais de `emissions.maxHourlyDistance` km (padrão 150) conta como zero: é salto de GPS ou hodômetro
   (a mesma trava que o front aplicava só na análise de instante agora vale para tudo).
4. Mês = soma das horas. O resumo (cards) = soma do intervalo, coerente com os gráficos.

Diferenças em relação ao cálculo antigo do front, que fazia `último totalDistance − primeiro totalDistance` dentro
de cada mês: a distância entre o último ponto de um mês e o primeiro do seguinte, que se perdia, agora entra; um
hodômetro zerado no meio do mês não gera mais distância errada; os cards usavam o resumo do Traccar
(`/api/reports/summary`) e agora usam a mesma soma dos gráficos.

### Valores de cada hora

Com `d` = km da hora e os parâmetros do dia dela:

| Grandeza | Fórmula | Unidade |
|---|---|---|
| Diesel | `d / dieselEfficiency` | L |
| Energia | `d × electricConsumption` | kWh |
| Custo diesel | `litros × dieselPrice` | R$ |
| Custo elétrico | `kWh × energyTariff` | R$ |
| Emissão diesel | `d × dieselEmissionFactor / 1000` | tCO2e |
| Emissão elétrica | `kWh × electricEmissionFactor / 1000` | tCO2e |

As duas versões são sempre calculadas: a do tipo do veículo é a real e a outra é a projeção usada nos
comparativos. `consumption`, `cost` e `emissions` de cada linha trazem a real.

Tipo do veículo pela categoria do dispositivo: as de `emissions.combustionCategories` (padrão `gasBus,gasShip`) são
a combustão, as demais elétricas. Antes o front tratava só `gasBus` como combustão, então `gasShip` saía como
elétrico.

Resumo: `efficiency = distância / consumo` (`null` sem consumo). Para elétricos, `emissionsAvoided` = emissão
diesel − emissão elétrica e `savings` = custo diesel − custo elétrico; para combustão, zero.

## Histórico dos parâmetros

### Parâmetros

| Chave | Unidade | Padrão (memorial UFPA) |
|---|---|---|
| `dieselEfficiency` | km/L | 2,5 |
| `dieselEmissionFactor` | kgCO2e/km | 1,44184 |
| `dieselPrice` | R$/L | 5,95 |
| `electricConsumption` | kWh/km | 0,8 |
| `electricEmissionFactor` | kgCO2e/kWh | 0,3497 |
| `energyTariff` | R$/kWh | 0,72 |

O padrão vale enquanto não houver valor cadastrado que se aplique à data.

### Registro

Tabela `tc_emission_factors` (changelog `schema/changelog-sse-1.0.0.xml`):

| Campo (JSON) | Coluna | Descrição |
|---|---|---|
| `id` | `id` | |
| `parameter` | `parameter` | Chave do parâmetro |
| `category` | `category` | Categoria de dispositivo; `null` = todas |
| `period` | `period` | `YYYY` (ano), `YYYY-MM` (mês) ou `YYYY-MM-DD` (dia), no fuso do relatório |
| `value` | `factorValue` | Valor (`value` é palavra reservada no H2) |
| `source` | `source` | Fonte, opcional (ex.: "MCTI, fator médio do SIN 03/2026") |
| `granularity` | — | Só leitura: `YEAR`, `MONTH` ou `DAY`, derivado de `period` |

Validação: parâmetro conhecido, período válido, valor finito ≥ 0 (> 0 para `dieselEfficiency` e
`electricConsumption`), um só valor por parâmetro + período + categoria.

### Qual valor vale num dia

Para um parâmetro, um dia e a categoria do dispositivo:

1. Valor cujo período **contém** o dia, o mais específico primeiro: dia, depois mês, depois ano.
2. Se nenhum contém, o valor cujo período **terminou mais recentemente** antes do dia continua valendo.
3. Antes do primeiro valor cadastrado, o padrão.

Em cada passo, um valor da categoria do dispositivo tem prioridade sobre um valor sem categoria.

Exemplo, fator da rede: `2025` = 0,30, `2026-03` = 0,40, `2026-03-15` = 0,45.

| Dia | Valor | Por quê |
|---|---|---|
| 10/06/2025 | 0,30 | Ano 2025 contém o dia |
| 10/01/2026 | 0,30 | Nada contém; o último terminado é 2025 |
| 14/03/2026 | 0,40 | Mês 2026-03 contém |
| 15/03/2026 | 0,45 | O dia é mais específico que o mês |
| 16/03/2026 | 0,40 | Mês 2026-03 contém |
| 10/05/2026 | 0,40 | Nada contém; o último terminado é 2026-03 (o dia 15/03 terminou antes) |
| 31/12/2024 | 0,3497 | Antes de tudo: padrão |

Implementação: `EmissionFactorResolver` (puro, testado em `EmissionFactorResolverTest`). O histórico fica em
memória (`EmissionFactorService`) e é recarregado a cada alteração pela API.

## API

Todas pedem login. Datas e horários ISO 8601. `timezone` é um nome IANA (ex.: `America/Belem`); sem ele, vale o
fuso do usuário ou do servidor (atributo `timezone`). Erros de parâmetro respondem `400` com o motivo em texto.

### `GET /api/v1/reports/emissions/fleet?from=YYYY-MM&to=YYYY-MM&timezone=`

Comparativo da frota. Padrão: janeiro a dezembro do ano corrente. Administrador recebe todos os dispositivos; os
demais, os seus.

```json
{
  "timezone": "America/Belem", "from": "2026-01", "to": "2026-12",
  "months": [{ "period": "2026-01", "label": "JAN/26" }],
  "devices": [{
    "device": { "id": 1, "name": "Bus1", "category": "electricBus", "powerType": "ELECTRIC" },
    "units": { "distance": "km", "consumption": "kWh", "cost": "R$", "emissions": "tCO2e", "efficiency": "km/kWh" },
    "summary": { "...": "como no monthly" },
    "months": [{ "...": "linha, abaixo" }]
  }]
}
```

### `GET /api/v1/reports/emissions/monthly?deviceId=&from=YYYY-MM&to=YYYY-MM&timezone=`

Padrão: janeiro do ano corrente até o mês corrente. Intervalo máximo: `emissions.monthlyMaxMonths` (60).

```json
{
  "device": { "id": 1, "name": "Bus1", "category": "electricBus", "powerType": "ELECTRIC" },
  "timezone": "America/Belem", "from": "2026-10", "to": "2026-11",
  "units": { "distance": "km", "consumption": "kWh", "cost": "R$", "emissions": "tCO2e", "efficiency": "km/kWh" },
  "summary": {
    "distanceKm": 18.03, "consumption": 14.43, "cost": 10.39, "emissions": 0.0050,
    "efficiency": 1.25, "emissionsAvoided": 0.0210, "savings": 32.53
  },
  "months": [{
    "period": "2026-11", "label": "NOV/26",
    "start": "2026-11-01T03:00:00.000+00:00", "end": "2026-12-01T03:00:00.000+00:00",
    "distanceKm": 18.03, "consumption": 14.43, "cost": 10.39, "emissions": 0.0050,
    "dieselLiters": 7.21, "electricKwh": 14.43, "dieselCost": 42.92, "electricCost": 10.39,
    "dieselEmissions": 0.0260, "electricEmissions": 0.0050
  }],
  "factors": [{
    "parameter": "electricEmissionFactor", "unit": "kgCO2e/kWh", "from": "2026-11-01", "to": "2026-11-30",
    "value": 0.55, "factorId": 1, "period": "2026-11", "source": "MCTI"
  }]
}
```

`factors` é o memorial de cálculo: para cada parâmetro, os trechos de dias seguidos com o mesmo valor
(`factorId` `null` = padrão).

### `GET /api/v1/reports/emissions/instant?deviceId=&from=&to=&timezone=`

Uma linha por hora a partir de `from` (a última pode ser parcial). Rótulo `HH:mm`, ou `dd/MM HHh` acima de 24
horas. Intervalo máximo: `emissions.instantMaxDays` (31). Traz também `route`, o trajeto como `[[lat, lon], ...]`
com no máximo `emissions.routeMaxPoints` (2000) pontos, distribuídos por igual (primeiro e último sempre ficam), e
`factors` dos dias do intervalo.

### `GET /api/v1/emission-factors?parameter=&category=`

Lista do histórico, por parâmetro e período.

### `GET /api/v1/emission-factors/parameters`

`[{ key, unit, defaultValue, description }]`.

### `GET /api/v1/emission-factors/effective?date=YYYY-MM-DD&category=`

Valor de cada parâmetro na data (padrão: hoje), com `factorId`, `period`, `category` e `source` de onde veio.

### `POST /api/v1/emission-factors`, `PUT /api/v1/emission-factors/{id}`, `DELETE /api/v1/emission-factors/{id}`

Só administrador. Corpo:

```json
{ "parameter": "electricEmissionFactor", "period": "2026-03", "category": null, "value": 0.0385,
  "source": "MCTI, fator médio do SIN 03/2026" }
```

Ficam no log de auditoria do Traccar (`LogAction`). Exemplo:

```bash
curl -u admin@exemplo:senha -H 'Content-Type: application/json' \
  -d '{"parameter":"dieselPrice","period":"2026","value":6.10,"source":"ANP média anual"}' \
  https://SERVIDOR/api/v1/emission-factors
```

## Configuração

| Chave | Padrão | Descrição |
|---|---|---|
| `emissions.combustionCategories` | `gasBus,gasShip` | Categorias a combustão; as demais são elétricas |
| `emissions.maxHourlyDistance` | `150` | km acima dos quais uma hora conta como zero (salto) |
| `emissions.instantMaxDays` | `31` | Intervalo máximo do relatório por hora |
| `emissions.monthlyMaxMonths` | `60` | Intervalo máximo dos relatórios mensais |
| `emissions.routeMaxPoints` | `2000` | Pontos máximos do trajeto |

## Desempenho

O servidor lê as posições em stream, só com as colunas necessárias, e guarda um `double` por hora; o trajeto é
afinado durante a leitura, então a memória não cresce com o tamanho do intervalo. O comparativo anual ainda lê
todas as posições do ano de cada dispositivo (um após o outro) a cada abertura da tela. Se ficar lento com muitos
dispositivos, o próximo passo é guardar a distância por hora dos dias já fechados (tabela ou cache), já que eles
não mudam.

## Código

| Arquivo | Papel |
|---|---|
| `src/main/java/org/traccar/emission/EmissionParameter.java` | Parâmetros, unidades e padrões |
| `src/main/java/org/traccar/emission/EmissionFactor.java` | Registro do histórico (`tc_emission_factors`) |
| `src/main/java/org/traccar/emission/EmissionFactorResolver.java` | Qual valor vale num dia |
| `src/main/java/org/traccar/emission/EmissionFactorService.java` | Cache, validação e gravação do histórico |
| `src/main/java/org/traccar/emission/HourlyDistance.java` | Distância por hora e trajeto |
| `src/main/java/org/traccar/emission/EmissionAmounts.java` | Fórmulas de consumo, custo e emissão |
| `src/main/java/org/traccar/emission/EmissionReportService.java` | Relatórios de frota, mensal e por hora |
| `src/main/java/org/traccar/emission/EmissionReports.java` | Formato das respostas |
| `src/main/java/org/traccar/api/resource/v1/EmissionReportResource.java` | `/api/v1/reports/emissions/*` |
| `src/main/java/org/traccar/api/resource/v1/EmissionFactorResource.java` | `/api/v1/emission-factors` |
| `schema/changelog-sse-1.0.0.xml` | Tabela `tc_emission_factors` |
| `traccar-web/src/api/emissions.ts` | Cliente do front |
| `traccar-web/src/pages/settings/reports.vue` | Tela de relatórios (só desenha) |
| `traccar-web/src/pages/settings/emission-factors.vue` | Tela do histórico (administrador) |

Testes: `EmissionFactorResolverTest`, `HourlyDistanceTest`, `EmissionReportServiceTest`.
