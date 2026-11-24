package dobradicas.model;

/**
 * Representa uma peça (tag <Panel>) dentro do arquivo .scx.
 * comprimento = atributo Length (eixo X) -> usado como LARGURA_TOTAL no cálculo
 * largura     = atributo Width  (eixo Y)
 */
public class Painel {

    private final String id;
    private final double comprimento;
    private final double largura;
    private final double espessura;
    private final int dobradicasExistentes;

    public Painel(String id, double comprimento, double largura, double espessura, int dobradicasExistentes) {
        this.id = id;
        this.comprimento = comprimento;
        this.largura = largura;
        this.espessura = espessura;
        this.dobradicasExistentes = dobradicasExistentes;
    }

    public String getId() {
        return id;
    }

    public double getComprimento() {
        return comprimento;
    }

    public double getLargura() {
        return largura;
    }

    public double getEspessura() {
        return espessura;
    }

    public int getDobradicasExistentes() {
        return dobradicasExistentes;
    }
}
