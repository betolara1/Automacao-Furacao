package dobradicas.model;

import java.nio.file.Path;
import java.util.List;

/**
 * Um arquivo .scx aberto na tela, com as peças que foram lidas dele.
 */
public class ArquivoScx {

    private final Path caminho;
    private final List<Painel> paineis;
    private String status;

    public ArquivoScx(Path caminho, List<Painel> paineis) {
        this.caminho = caminho;
        this.paineis = paineis;
        this.status = "Aguardando";
    }

    public Path getCaminho() {
        return caminho;
    }

    public String getNome() {
        return caminho.getFileName().toString();
    }

    public List<Painel> getPaineis() {
        return paineis;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
