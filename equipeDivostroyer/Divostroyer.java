package equipeDivostroyer;

import robocode.AdvancedRobot;
import robocode.HitRobotEvent;
import robocode.RobotDeathEvent;
import robocode.Rules;
import robocode.ScannedRobotEvent;
import robocode.WinEvent;
import robocode.util.Utils;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Divostroyer - robô para o Robocode FAEX 2026 (formato Melee).
 *
 * Estratégia:
 *  - Radar: giro contínuo em 360° para conhecer todos os inimigos; quando
 *    sobra apenas um, trava o radar nele.
 *  - Movimento "risco mínimo": a cada turno sorteia pontos ao redor e vai para
 *    o mais seguro (longe de inimigos fortes, sem ser o alvo mais próximo de
 *    ninguém, andando de lado em relação a eles e longe das paredes).
 *  - Desvio: quando percebe que um inimigo próximo atirou (queda de energia
 *    dele entre 0.1 e 3), muda de rota na hora.
 *  - Mira com 4 "armas virtuais": tiro direto, predição linear, predição
 *    circular e GuessFactor (aprende para que lado cada inimigo costuma fugir).
 *    Para cada inimigo o robô simula as 4 armas ao mesmo tempo e usa a que
 *    mais estaria acertando ele.
 *  - O aprendizado (estatísticas e notas das armas) fica em campos static,
 *    então continua valendo de um round para o outro dentro da batalha.
 */
public class Divostroyer extends AdvancedRobot {

    /** Distância mínima das paredes (o robô tem 36x36 px, metade = 18). */
    private static final double MARGEM_PAREDE = 40;
    /** Quantos pontos candidatos o movimento avalia por turno. */
    private static final int PONTOS_CANDIDATOS = 36;

    // Armas virtuais
    private static final int TIRO_DIRETO = 0;
    private static final int LINEAR = 1;
    private static final int CIRCULAR = 2;
    private static final int GUESS_FACTOR = 3;
    private static final int TOTAL_ARMAS = 4;

    // GuessFactor: 31 faixas de -1 (fugiu para trás) a +1 (fugiu para frente)
    private static final int FAIXAS = 31;
    private static final int FAIXA_CENTRAL = (FAIXAS - 1) / 2;
    private static final int SEG_DISTANCIA = 5;
    private static final int SEG_VELOCIDADE = 3;

    /** Aprendizado por inimigo, mantido entre os rounds. */
    private static final Map<String, double[][][]> estatisticasGF = new HashMap<>();
    private static final Map<String, double[]> notasArmas = new HashMap<>();

    private static final Color ROSA = new Color(255, 105, 180);
    private static final Color ROSA_FORTE = new Color(255, 20, 147);
    private static final Color ROSA_CLARO = new Color(255, 182, 193);

    private final Map<String, Inimigo> inimigos = new HashMap<>();
    private Inimigo alvo;

    private Rectangle2D.Double areaSegura;
    private Point2D.Double minhaPos;
    private Point2D.Double destino;
    private Point2D.Double posAnterior;
    private Point2D.Double miraPrevista;
    private boolean inimigoAtirou;

    // ------------------------------------------------------------------
    // Ciclo principal
    // ------------------------------------------------------------------

    @Override
    public void run() {
        setBodyColor(ROSA);
        setGunColor(ROSA_FORTE);
        setRadarColor(ROSA_CLARO);
        setBulletColor(ROSA_FORTE);
        setScanColor(ROSA);

        // Corpo, canhão e radar giram de forma independente.
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);

        areaSegura = new Rectangle2D.Double(
                MARGEM_PAREDE, MARGEM_PAREDE,
                getBattleFieldWidth() - 2 * MARGEM_PAREDE,
                getBattleFieldHeight() - 2 * MARGEM_PAREDE);

        minhaPos = new Point2D.Double(getX(), getY());
        posAnterior = minhaPos;
        destino = minhaPos;

        setTurnRadarRightRadians(Double.POSITIVE_INFINITY);

        while (true) {
            minhaPos = new Point2D.Double(getX(), getY());

            // Se o radar parou (ex.: alvo travado sumiu), volta a girar.
            if (getRadarTurnRemainingRadians() == 0) {
                setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
            }

            mover();
            mirarEAtirar();
            execute();
        }
    }

    // ------------------------------------------------------------------
    // Radar / registro de inimigos
    // ------------------------------------------------------------------

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        minhaPos = new Point2D.Double(getX(), getY());
        double anguloAbs = getHeadingRadians() + e.getBearingRadians();
        double x = getX() + Math.sin(anguloAbs) * e.getDistance();
        double y = getY() + Math.cos(anguloAbs) * e.getDistance();

        Inimigo ini = inimigos.get(e.getName());
        if (ini == null) {
            ini = new Inimigo(e.getName());
            inimigos.put(e.getName(), ini);
        }

        // Queda de energia típica de tiro: hora de mudar de rota.
        double queda = ini.energia - e.getEnergy();
        if (ini.ultimaVez >= 0 && queda >= 0.1 && queda <= 3.0 && e.getDistance() < 400) {
            inimigoAtirou = true;
        }

        ini.atualizar(x, y, e.getEnergy(), e.getHeadingRadians(), e.getVelocity(), getTime());

        processarOndas(ini);
        criarOnda(ini);
        escolherAlvo();

        // No 1x1 trava o radar no oponente (varredura um pouco mais larga
        // que o necessário para não perdê-lo).
        if (getOthers() == 1) {
            double giro = Utils.normalRelativeAngle(anguloAbs - getRadarHeadingRadians());
            setTurnRadarRightRadians(giro * 2);
        }
    }

    @Override
    public void onRobotDeath(RobotDeathEvent e) {
        inimigos.remove(e.getName());
        if (alvo != null && alvo.nome.equals(e.getName())) {
            alvo = null;
            escolherAlvo();
        }
    }

    /**
     * Prefere inimigos próximos e com pouca energia. Só troca de alvo se o
     * novo for claramente melhor, para não ficar mudando a mira toda hora.
     */
    private void escolherAlvo() {
        Inimigo melhor = alvo;
        double melhorCusto = alvo == null ? Double.POSITIVE_INFINITY : custoAlvo(alvo) * 0.8;
        for (Inimigo ini : inimigos.values()) {
            double custo = custoAlvo(ini);
            if (custo < melhorCusto) {
                melhorCusto = custo;
                melhor = ini;
            }
        }
        alvo = melhor;
    }

    private double custoAlvo(Inimigo ini) {
        double dist = ini.pos.distance(getX(), getY());
        double idade = getTime() - ini.ultimaVez;
        // Informação velha vale menos; energia alta torna o alvo mais caro.
        return dist * (ini.energia + 40) / 100 + idade * 15;
    }

    // ------------------------------------------------------------------
    // Movimento de risco mínimo
    // ------------------------------------------------------------------

    private void mover() {
        // Inimigo desativado (energia zero, parado): bater nele dá pontos de colisão.
        if (alvo != null && alvo.energia == 0 && getTime() - alvo.ultimaVez < 5) {
            destino = alvo.pos;
            irPara(destino);
            return;
        }

        if (inimigoAtirou) {
            // Força escolher uma rota nova a partir daqui.
            inimigoAtirou = false;
            posAnterior = minhaPos;
            destino = minhaPos;
        }

        double distInimigoMaisProximo = Double.POSITIVE_INFINITY;
        for (Inimigo ini : inimigos.values()) {
            distInimigoMaisProximo = Math.min(distInimigoMaisProximo, ini.pos.distance(minhaPos));
        }

        // Ponto atual de destino concorre com os novos candidatos.
        double melhorRisco = risco(destino);
        Point2D.Double melhorPonto = destino;

        double raioMax = Math.min(distInimigoMaisProximo * 0.8, 220);
        double raioMin = Math.min(100, raioMax);
        for (int i = 0; i < PONTOS_CANDIDATOS; i++) {
            double ang = Math.random() * 2 * Math.PI;
            double raio = raioMin + Math.random() * (raioMax - raioMin);
            Point2D.Double p = projetar(minhaPos, ang, raio);
            if (!areaSegura.contains(p)) {
                continue;
            }
            double r = risco(p);
            if (r < melhorRisco) {
                melhorRisco = r;
                melhorPonto = p;
            }
        }

        if (melhorPonto != destino) {
            posAnterior = minhaPos;
            destino = melhorPonto;
        }

        // Chegou ao destino: força escolher outro no próximo turno.
        if (minhaPos.distance(destino) < 15) {
            destino = minhaPos;
        }

        irPara(destino);
    }

    private double risco(Point2D.Double p) {
        // Evita voltar para onde estava (movimento previsível).
        double r = 0.08 / Math.max(1, p.distanceSq(posAnterior));
        // Evita ficar parado.
        r += 0.1 / Math.max(1, p.distanceSq(minhaPos));

        double minhaEnergia = Math.max(getEnergy(), 1);
        double angMovimento = angulo(minhaPos, p);

        for (Inimigo ini : inimigos.values()) {
            double ameaca = Math.min(ini.energia / minhaEnergia, 2.0) + 0.5;
            // Andar em direção ao inimigo (ou fugindo em linha reta) é pior
            // que andar perpendicular a ele.
            double alinhamento = Math.abs(Math.cos(angMovimento - angulo(minhaPos, ini.pos)));
            // Se nesse ponto eu seria o robô mais próximo desse inimigo,
            // provavelmente eu viro o alvo dele: perigo dobrado.
            if (souMaisProximoDe(ini, p)) {
                ameaca *= 2;
            }
            r += ameaca * (1 + alinhamento) / Math.max(1, p.distanceSq(ini.pos));
        }

        // No 1x1 não vale encostar demais no oponente.
        if (getOthers() == 1 && alvo != null && p.distance(alvo.pos) < 150) {
            r *= 2;
        }
        return r;
    }

    private boolean souMaisProximoDe(Inimigo ini, Point2D.Double p) {
        double minhaDist = p.distanceSq(ini.pos);
        for (Inimigo outro : inimigos.values()) {
            if (outro != ini && outro.pos.distanceSq(ini.pos) < minhaDist) {
                return false;
            }
        }
        return true;
    }

    /** Vai até o ponto, andando de ré quando for mais rápido. */
    private void irPara(Point2D.Double p) {
        double dist = minhaPos.distance(p);
        double giro = Utils.normalRelativeAngle(angulo(minhaPos, p) - getHeadingRadians());
        int sentido = 1;
        if (Math.abs(giro) > Math.PI / 2) {
            giro = Utils.normalRelativeAngle(giro + Math.PI);
            sentido = -1;
        }
        setTurnRightRadians(giro);
        // Curva fechada: reduz a velocidade para não abrir demais a trajetória.
        setMaxVelocity(Math.abs(giro) > Math.PI / 4 ? 4 : Rules.MAX_VELOCITY);
        setAhead(dist * sentido);
    }

    // ------------------------------------------------------------------
    // Ondas: aprendizado da mira
    // ------------------------------------------------------------------

    /**
     * A cada scan "dispara" uma bala imaginária (onda) no inimigo e anota para
     * onde cada uma das 4 armas teria mirado. Quando a onda alcança o inimigo,
     * vemos onde ele realmente estava: isso alimenta o GuessFactor e dá nota
     * para as armas.
     */
    private void criarOnda(Inimigo ini) {
        double dist = ini.pos.distance(minhaPos);
        double potencia = Math.max(potenciaDoTiro(dist, ini.energia), 0.1);

        Onda onda = new Onda();
        onda.origem = minhaPos;
        onda.tempo = getTime();
        onda.velocidade = Rules.getBulletSpeed(potencia);
        onda.anguloBase = angulo(minhaPos, ini.pos);
        onda.direcao = direcaoLateral(ini, onda.anguloBase);
        onda.segDist = segmentoDistancia(dist);
        onda.segVel = segmentoVelocidade(ini, onda.anguloBase);
        onda.angulosArmas = new double[TOTAL_ARMAS];
        for (int arma = 0; arma < TOTAL_ARMAS; arma++) {
            onda.angulosArmas[arma] = anguloDaArma(arma, ini, minhaPos, onda.velocidade, 0);
        }

        ini.ondas.add(onda);
        if (ini.ondas.size() > 40) {
            ini.ondas.remove(0);
        }
    }

    private void processarOndas(Inimigo ini) {
        double[][][] stats = estatisticasDe(ini.nome);
        double[] notas = notasDe(ini.nome);

        Iterator<Onda> it = ini.ondas.iterator();
        while (it.hasNext()) {
            Onda onda = it.next();
            double percorrido = (getTime() - onda.tempo) * onda.velocidade;
            double distAlvo = onda.origem.distance(ini.pos);
            if (percorrido < distAlvo - 18) {
                continue;
            }
            it.remove();

            double anguloReal = angulo(onda.origem, ini.pos);

            // GuessFactor: em que fração do ângulo máximo de fuga ele estava?
            double desvio = Utils.normalRelativeAngle(anguloReal - onda.anguloBase);
            double gf = Math.max(-1, Math.min(1,
                    desvio / anguloMaximoFuga(onda.velocidade) * onda.direcao));
            int faixa = (int) Math.round(gf * FAIXA_CENTRAL) + FAIXA_CENTRAL;
            double[] seg = stats[onda.segDist][onda.segVel];
            for (int i = 0; i < FAIXAS; i++) {
                seg[i] = seg[i] * 0.97 + 1.0 / (1 + (i - faixa) * (i - faixa));
            }

            // Notas das armas: quem teria acertado ganha ponto.
            double tolerancia = Math.atan(18 / Math.max(distAlvo, 1));
            for (int arma = 0; arma < TOTAL_ARMAS; arma++) {
                boolean acertou = Math.abs(Utils.normalRelativeAngle(
                        onda.angulosArmas[arma] - anguloReal)) < tolerancia;
                notas[arma] = notas[arma] * 0.95 + (acertou ? 0.05 : 0);
            }
        }
    }

    // ------------------------------------------------------------------
    // Mira e tiro
    // ------------------------------------------------------------------

    private void mirarEAtirar() {
        if (alvo == null) {
            miraPrevista = null;
            return;
        }

        double dist = alvo.pos.distance(minhaPos);
        double potencia = potenciaDoTiro(dist, alvo.energia);

        // Atira com a mira calculada no turno anterior, se o canhão já chegou lá.
        if (potencia > 0 && getGunHeat() == 0
                && Math.abs(getGunTurnRemainingRadians()) < Math.atan(18 / Math.max(dist, 1))
                && getTime() - alvo.ultimaVez < 10) {
            setFire(potencia);
        }

        double velBala = Rules.getBulletSpeed(Math.max(potencia, 0.1));
        long ticksDesdeScan = Math.max(0, getTime() - alvo.ultimaVez);
        double anguloTiro = anguloDaArma(melhorArma(alvo), alvo, minhaPos, velBala, ticksDesdeScan);

        miraPrevista = projetar(minhaPos, anguloTiro, dist);
        setTurnGunRightRadians(Utils.normalRelativeAngle(anguloTiro - getGunHeadingRadians()));
    }

    private int melhorArma(Inimigo ini) {
        double[] notas = notasDe(ini.nome);
        int melhor = CIRCULAR;
        for (int arma = 0; arma < TOTAL_ARMAS; arma++) {
            if (notas[arma] > notas[melhor]) {
                melhor = arma;
            }
        }
        return melhor;
    }

    /**
     * Ângulo absoluto para onde cada arma atiraria.
     *
     * @param ticksDesdeScan quantos turnos se passaram desde a última vez que o
     *                       inimigo foi visto (a posição é estimada até agora).
     */
    private double anguloDaArma(int arma, Inimigo ini, Point2D.Double origem,
                                double velBala, long ticksDesdeScan) {
        switch (arma) {
            case LINEAR:
                return angulo(origem, simular(ini, origem, velBala, false, ticksDesdeScan));
            case CIRCULAR:
                return angulo(origem, simular(ini, origem, velBala, true, ticksDesdeScan));
            case GUESS_FACTOR: {
                Point2D.Double agora = simular(ini, origem, Double.POSITIVE_INFINITY, true, ticksDesdeScan);
                double base = angulo(origem, agora);
                double dist = origem.distance(agora);
                double[] seg = estatisticasDe(ini.nome)[segmentoDistancia(dist)][segmentoVelocidade(ini, base)];
                int melhor = FAIXA_CENTRAL;
                for (int i = 0; i < FAIXAS; i++) {
                    if (seg[i] > seg[melhor]) {
                        melhor = i;
                    }
                }
                double gf = (double) (melhor - FAIXA_CENTRAL) / FAIXA_CENTRAL;
                return base + direcaoLateral(ini, base) * gf * anguloMaximoFuga(velBala);
            }
            case TIRO_DIRETO:
            default:
                return angulo(origem, simular(ini, origem, Double.POSITIVE_INFINITY, true, ticksDesdeScan));
        }
    }

    /**
     * Simula o inimigo mantendo velocidade (e a curva, se {@code comCurva})
     * primeiro até o momento atual e depois até a bala o alcançar.
     * Se ele bater na parede, para ali. Com velBala infinita, só estima a
     * posição atual.
     */
    private Point2D.Double simular(Inimigo ini, Point2D.Double origem, double velBala,
                                   boolean comCurva, long ticksDesdeScan) {
        double heading = ini.heading;
        double x = ini.pos.x;
        double y = ini.pos.y;
        double largura = getBattleFieldWidth();
        double altura = getBattleFieldHeight();

        int t = 0;
        while (t < 100) {
            if (t >= ticksDesdeScan && (Double.isInfinite(velBala)
                    || (t - ticksDesdeScan) * velBala >= origem.distance(x, y))) {
                break;
            }
            if (comCurva) {
                heading += ini.taxaCurva;
            }
            double nx = x + Math.sin(heading) * ini.velocidade;
            double ny = y + Math.cos(heading) * ini.velocidade;
            if (nx < 18 || ny < 18 || nx > largura - 18 || ny > altura - 18) {
                break;
            }
            x = nx;
            y = ny;
            t++;
        }
        return new Point2D.Double(x, y);
    }

    private double potenciaDoTiro(double dist, double energiaInimigo) {
        double p;
        if (dist < 150) {
            p = 3;
        } else if (dist < 350) {
            p = 2.2;
        } else {
            p = 1.4;
        }
        // Com muita gente na arena, o tiro de longe raramente acerta.
        if (getOthers() > 4 && dist > 300) {
            p = Math.min(p, 1.2);
        }
        // Não gasta mais do que o necessário para eliminar o alvo.
        p = Math.min(p, energiaParaMatar(energiaInimigo));
        // Economiza quando está fraco (cada tiro custa a própria energia).
        if (getEnergy() < 20) {
            p = Math.min(p, getEnergy() / 10);
        }
        if (getEnergy() < 1.2) {
            return 0;
        }
        return Math.max(Rules.MIN_BULLET_POWER, Math.min(Rules.MAX_BULLET_POWER, p));
    }

    /** Menor potência cujo dano elimina um inimigo com essa energia. */
    private static double energiaParaMatar(double energia) {
        if (energia <= 4) {
            return energia / 4 + 0.1;
        }
        // dano = 4p + 2(p - 1) quando p > 1  ->  p = (energia + 2) / 6
        return (energia + 2) / 6 + 0.1;
    }

    // ------------------------------------------------------------------
    // Eventos extras
    // ------------------------------------------------------------------

    /** Encostou num robô: aproveita e atira à queima-roupa. */
    @Override
    public void onHitRobot(HitRobotEvent e) {
        double giro = Utils.normalRelativeAngle(
                getHeadingRadians() + e.getBearingRadians() - getGunHeadingRadians());
        setTurnGunRightRadians(giro);
        if (getGunHeat() == 0 && getEnergy() > 5 && Math.abs(giro) < 0.3) {
            setFire(Math.min(3, energiaParaMatar(e.getEnergy())));
        }
    }

    @Override
    public void onWin(WinEvent e) {
        setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
        setTurnRight(3600);
        execute();
    }

    /**
     * Debug visual. Regulamento: no máximo 3 elementos por turno.
     * Aqui são 2: círculo onde a arma escolhida vai mirar e linha até o destino.
     */
    @Override
    public void onPaint(Graphics2D g) {
        if (miraPrevista != null) {
            g.setColor(ROSA_FORTE);
            g.drawOval((int) miraPrevista.x - 20, (int) miraPrevista.y - 20, 40, 40);
        }
        if (destino != null && minhaPos != null) {
            g.setColor(ROSA_CLARO);
            g.drawLine((int) minhaPos.x, (int) minhaPos.y, (int) destino.x, (int) destino.y);
        }
    }

    // ------------------------------------------------------------------
    // Utilitários
    // ------------------------------------------------------------------

    /** Ângulo absoluto (padrão Robocode: 0 = norte, sentido horário). */
    private static double angulo(Point2D.Double de, Point2D.Double para) {
        return Math.atan2(para.x - de.x, para.y - de.y);
    }

    private static Point2D.Double projetar(Point2D.Double origem, double ang, double dist) {
        return new Point2D.Double(origem.x + Math.sin(ang) * dist, origem.y + Math.cos(ang) * dist);
    }

    /** Maior ângulo que um robô a 8 px/turno consegue fugir de uma bala. */
    private static double anguloMaximoFuga(double velBala) {
        return Math.asin(Rules.MAX_VELOCITY / velBala);
    }

    /** +1 se o inimigo anda no sentido horário em relação a mim, -1 se anti-horário. */
    private static int direcaoLateral(Inimigo ini, double anguloAbs) {
        double lateral = ini.velocidade * Math.sin(ini.heading - anguloAbs);
        if (Math.abs(lateral) > 0.1) {
            ini.ultimaDirecao = lateral > 0 ? 1 : -1;
        }
        return ini.ultimaDirecao;
    }

    private static int segmentoDistancia(double dist) {
        return Math.min(SEG_DISTANCIA - 1, (int) (dist / 150));
    }

    private static int segmentoVelocidade(Inimigo ini, double anguloAbs) {
        double lateral = Math.abs(ini.velocidade * Math.sin(ini.heading - anguloAbs));
        if (lateral < 2) {
            return 0;
        }
        return lateral < 6 ? 1 : 2;
    }

    private static double[][][] estatisticasDe(String nome) {
        double[][][] s = estatisticasGF.get(nome);
        if (s == null) {
            s = new double[SEG_DISTANCIA][SEG_VELOCIDADE][FAIXAS];
            estatisticasGF.put(nome, s);
        }
        return s;
    }

    private static double[] notasDe(String nome) {
        double[] n = notasArmas.get(nome);
        if (n == null) {
            // Começa confiando na circular até ter dados.
            n = new double[TOTAL_ARMAS];
            n[CIRCULAR] = 0.02;
            notasArmas.put(nome, n);
        }
        return n;
    }

    /** Bala imaginária usada para aprender como o inimigo se move. */
    private static class Onda {
        Point2D.Double origem;
        long tempo;
        double velocidade;
        double anguloBase;
        int direcao;
        int segDist;
        int segVel;
        double[] angulosArmas;
    }

    /** Último estado conhecido de um inimigo. */
    private static class Inimigo {
        final String nome;
        Point2D.Double pos = new Point2D.Double();
        double energia;
        double heading;
        double velocidade;
        double taxaCurva;
        int ultimaDirecao = 1;
        long ultimaVez = -1;
        final List<Onda> ondas = new ArrayList<>();

        Inimigo(String nome) {
            this.nome = nome;
        }

        void atualizar(double x, double y, double energia, double heading, double velocidade, long agora) {
            if (ultimaVez >= 0 && agora > ultimaVez) {
                double curva = Utils.normalRelativeAngle(heading - this.heading) / (agora - ultimaVez);
                // Limita à curva máxima possível no Robocode (10°/turno).
                double max = Math.toRadians(Rules.MAX_TURN_RATE);
                taxaCurva = Math.max(-max, Math.min(max, curva));
            }
            this.pos = new Point2D.Double(x, y);
            this.energia = energia;
            this.heading = heading;
            this.velocidade = velocidade;
            this.ultimaVez = agora;
        }
    }
}
