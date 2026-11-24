package dobradicas.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Regras de quantidade e posição das dobradiças.
 *
 * Quantidade (pela largura total da peça):
 *   até 900mm              -> 2 dobradiças (uma em cada ponta)
 *   > 900mm  e <= 1600mm   -> 3 dobradiças (pontas + meio)
 *   > 1600mm e <= 2200mm   -> 4 dobradiças
 *   > 2200mm               -> 5 dobradiças (o meio volta)
 *
 * Posições:
 *   1ª sempre em 100mm e a última sempre em LARGURA_TOTAL - 100mm.
 *   As do meio ficam divididas igualmente entre elas:
 *     3 -> (LARGURA_TOTAL - 200) / 2 + 100  (= meio da peça)
 *     4 -> ((LARGURA_TOTAL - 200) / 3) * n + 100
 *     5 -> ((LARGURA_TOTAL - 200) / 4) * n + 100
 */
public class CalculadoraDobradica {

    public static final double DISTANCIA_PONTA = 100.0;

    private static final double LIMITE_3_DOBRADICAS = 900.0;
    private static final double LIMITE_4_DOBRADICAS = 1600.0;
    private static final double LIMITE_5_DOBRADICAS = 2200.0;

    public int calcularQuantidade(double larguraTotal) {
        if (larguraTotal <= LIMITE_3_DOBRADICAS) {
            return 2;
        }
        if (larguraTotal <= LIMITE_4_DOBRADICAS) {
            return 3;
        }
        if (larguraTotal <= LIMITE_5_DOBRADICAS) {
            return 4;
        }
        return 5;
    }

    /**
     * Retorna as posições X (centro do furo de 35mm) de cada dobradiça, da esquerda para a direita.
     */
    public List<Double> calcularPosicoes(double larguraTotal) {
        if (larguraTotal <= DISTANCIA_PONTA * 2) {
            throw new IllegalArgumentException(
                    "Peça muito pequena para dobradiça: " + larguraTotal + "mm");
        }

        int quantidade = calcularQuantidade(larguraTotal);
        double espacoUtil = larguraTotal - (DISTANCIA_PONTA * 2);
        double passo = espacoUtil / (quantidade - 1);

        List<Double> posicoes = new ArrayList<>();
        for (int i = 0; i < quantidade; i++) {
            double x = (passo * i) + DISTANCIA_PONTA;
            posicoes.add(arredondar(x));
        }
        return posicoes;
    }

    /** Arredonda para 1 casa decimal. */
    private double arredondar(double valor) {
        return Math.round(valor * 10.0) / 10.0;
    }
}
