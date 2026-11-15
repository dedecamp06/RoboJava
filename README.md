# Robocode FAEX 2026 — robô `Divostroyer` 💗
# Feito por - Ana - 2026
## Utilizado Claude/Cursor

Robô para a Guerra de Robôs Digitais FAEX 2026 (formato **Melee**, arena 800x600).

```
robocode/
└── equipeDivostroyer/   <- package = nome da equipe (regra 7.3)
    └── Divostroyer.java <- arquivo a ser entregue
```

## Checklist do regulamento

| Regra | Situação |
|---|---|
| 2.4 / 7.1 — `extends AdvancedRobot` | ✅ |
| 2.5 / 7.2 — máx. 3 desenhos no `onPaint` | ✅ usa 2 (círculo no alvo previsto + linha até o destino) |
| 7.3 — nome da equipe no `package` | ✅ `package equipeDivostroyer;` |
| 7.3 — código autoral | ✅ escrito do zero, comentado em português |
| 7.4 — compila | ✅ testado no Robocode 1.12.0 |
| 8.1 — sem turnos pulados | ✅ 0 skipped turns no teste |
| 8.2 — sem exceptions | ✅ nenhuma no log |

## Como funciona a estratégia

- **Radar**: gira 360° sem parar para enxergar todos; quando sobra 1 inimigo, trava nele.
- **Movimento (risco mínimo)**: a cada turno sorteia 36 pontos ao redor e vai para o mais seguro — longe de inimigos fortes, evitando ser o robô mais próximo de alguém (vira alvo), andando de lado em relação a eles e sempre a 40 px das paredes.
- **Desvio**: quando um inimigo a menos de 400 px atira (a energia dele cai entre 0.1 e 3), muda de rota na hora.
- **Mira com 4 armas virtuais**: tiro direto, predição linear, predição circular e GuessFactor (aprende para que lado cada inimigo costuma fugir). A cada scan o robô simula as 4 e, para cada inimigo, usa a que mais estaria acertando. O aprendizado continua de um round para o outro.
- **Potência do tiro**: 3 de perto, 2.2 a média distância, 1.4 de longe; nunca gasta mais que o necessário para matar e economiza quando a própria energia está baixa.
- **Alvo**: prefere inimigos próximos e fracos (mais bônus de eliminação).
- **Colisão**: encostou, atira à queima-roupa; inimigo desativado (energia 0), vai pra cima dele (pontos de ramming).
- **Cores**: rosa 💗

## Testar no Robocode

1. Instalar: baixar `robocode-1.12.0-setup.jar` e rodar `java -jar robocode-1.12.0-setup.jar`.
2. Abrir o Robocode → **Robot → Source Editor → File → Open** → `Divostroyer.java` → **Compiler → Compile**.
3. **Battle → New**, adicionar `equipeDivostroyer.Divostroyer` + vários robôs `sample.*`.
4. Aba **Rules**: 800x600, Inactivity Time 450, Sentry Border 0.

Resultado dos testes (versão nova x versão anterior + robôs de exemplo, 0 skipped turns, 0 exceptions):

```
Melee 10 robôs (15 rounds):  Divostroyer 24%  | versão anterior 19% | SpinBot 9%
Final 4 robôs (20 rounds):   Divostroyer 51%  | versão anterior 35% | Walls 8%
1x1 (20 rounds):             Divostroyer 85%  | versão anterior 15%
```

## Ajustes para o Pit Stop (15 min antes da final)

Os pontos mais fáceis de mexer estão no topo/meio do arquivo:

- `MARGEM_PAREDE` — distância das paredes.
- `potenciaDoTiro()` — mais agressivo: aumentar os valores; mais econômico: diminuir.
- `custoAlvo()` — como escolhe quem atacar.
- `risco()` — como foge. Se estiver apanhando muito de perto, aumentar o `150` do 1x1.
