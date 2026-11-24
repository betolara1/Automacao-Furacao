package dobradicas.service;

import dobradicas.model.ArquivoScx;
import dobradicas.model.Painel;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leitura e alteração dos arquivos .scx.
 *
 * O arquivo é alterado como texto (e não reescrito por um parser XML) para manter
 * exatamente a formatação, a ordem dos atributos e os caracteres originais.
 * Só as linhas de furação de dobradiça são removidas e recriadas.
 * Depois da alteração o XML é validado antes de gravar.
 */
public class ScxService {

    // ---- Medidas da furação de dobradiça (padrão tirado do arquivo de exemplo) ----
    private static final double DIAMETRO_CANECO = 35.0;
    private static final double PROFUNDIDADE_CANECO = 12.0;
    private static final double DIAMETRO_FURO_FIXACAO = 5.1;
    private static final double PROFUNDIDADE_FURO_FIXACAO = 0.25;

    /** Distância do centro do caneco (35mm) até a borda de baixo do desenho (Y = Width - 22). */
    private static final double RECUO_Y_CANECO = 22.0;
    /** Distância dos furos de fixação até a borda de baixo do desenho (Y = Width - 28). */
    private static final double RECUO_Y_FIXACAO = 28.0;
    /** Distância no X entre o caneco e cada furo de fixação. */
    private static final double AFASTAMENTO_X_FIXACAO = 24.0;

    private static final String FACE = "5";
    private static final String PASTA_BACKUP = "backup";

    // ---- Regex usados para localizar as partes do arquivo ----
    private static final Pattern PADRAO_BLOCO_PAINEL =
            Pattern.compile("<Panel\\b[^>]*>.*?</Panel>", Pattern.DOTALL);
    private static final Pattern PADRAO_TAG_PAINEL =
            Pattern.compile("<Panel\\b[^>]*>");
    private static final Pattern PADRAO_ATRIBUTO =
            Pattern.compile("([\\w:]+)\\s*=\\s*\"([^\"]*)\"");
    /** Linha inteira de um <Machining ... /> sem filhos (as fresagens têm filhos e não entram aqui). */
    private static final Pattern PADRAO_LINHA_MACHINING_SIMPLES =
            Pattern.compile("[ \\t]*<Machining\\b[^>]*?/>[ \\t]*(\\r?\\n)?");
    private static final Pattern PADRAO_MACHINES_ABERTO =
            Pattern.compile("([ \\t]*)<Machines>(\\r?\\n)?");
    private static final Pattern PADRAO_MACHINES_VAZIO =
            Pattern.compile("([ \\t]*)<Machines\\s*/>");

    private static final byte[] BOM_UTF8 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final DateTimeFormatter FORMATO_DATA_BACKUP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final CalculadoraDobradica calculadora;

    public ScxService(CalculadoraDobradica calculadora) {
        this.calculadora = calculadora;
    }

    // =====================================================================
    // Leitura
    // =====================================================================

    public ArquivoScx ler(Path caminho) throws IOException {
        ConteudoArquivo conteudo = lerConteudo(caminho);
        validarXml(conteudo.texto);

        List<Painel> paineis = new ArrayList<>();
        Matcher blocos = PADRAO_BLOCO_PAINEL.matcher(conteudo.texto);
        while (blocos.find()) {
            paineis.add(montarPainel(blocos.group()));
        }

        if (paineis.isEmpty()) {
            throw new IOException("Nenhuma peça (<Panel>) encontrada no arquivo.");
        }
        return new ArquivoScx(caminho, paineis);
    }

    private Painel montarPainel(String blocoPainel) throws IOException {
        Map<String, String> atributos = lerAtributosDoPainel(blocoPainel);

        String id = atributos.getOrDefault("ID", "(sem ID)");
        double comprimento = lerNumero(atributos, "Length", id);
        double largura = lerNumero(atributos, "Width", id);
        double espessura = lerNumero(atributos, "Thickness", id);

        int dobradicasExistentes = 0;
        Matcher linhas = PADRAO_LINHA_MACHINING_SIMPLES.matcher(blocoPainel);
        while (linhas.find()) {
            Map<String, String> attrFuro = lerAtributos(linhas.group());
            if (isFuroDobradica(attrFuro) && isCaneco(attrFuro)) {
                dobradicasExistentes++;
            }
        }

        return new Painel(id, comprimento, largura, espessura, dobradicasExistentes);
    }

    // =====================================================================
    // Atualização
    // =====================================================================

    /**
     * Faz o backup do arquivo original, recria as furações de dobradiça de todas as peças
     * e grava por cima do arquivo original.
     *
     * @return resumo do que foi feito em cada peça (para mostrar no log)
     */
    public List<String> atualizar(ArquivoScx arquivo) throws IOException {
        Path caminho = arquivo.getCaminho();
        ConteudoArquivo conteudo = lerConteudo(caminho);

        List<String> resumo = new ArrayList<>();
        Matcher blocos = PADRAO_BLOCO_PAINEL.matcher(conteudo.texto);
        StringBuilder novoTexto = new StringBuilder();

        while (blocos.find()) {
            String blocoAtualizado = processarPainel(blocos.group(), conteudo.quebraLinha, resumo);
            blocos.appendReplacement(novoTexto, Matcher.quoteReplacement(blocoAtualizado));
        }
        blocos.appendTail(novoTexto);

        String textoFinal = novoTexto.toString();
        validarXml(textoFinal);

        Path backup = fazerBackup(caminho);
        resumo.add("Backup: " + backup);

        gravar(caminho, textoFinal, conteudo.temBom);
        return resumo;
    }

    private String processarPainel(String bloco, String quebraLinha, List<String> resumo) throws IOException {
        Map<String, String> atributosPainel = lerAtributosDoPainel(bloco);
        String id = atributosPainel.getOrDefault("ID", "(sem ID)");
        double comprimento = lerNumero(atributosPainel, "Length", id);
        double largura = lerNumero(atributosPainel, "Width", id);

        List<Double> posicoes;
        try {
            posicoes = calculadora.calcularPosicoes(comprimento);
        } catch (IllegalArgumentException e) {
            throw new IOException("Peça " + id + ": " + e.getMessage());
        }

        // 1) remove as furações de dobradiça que já existem
        int removidos = 0;
        Matcher linhas = PADRAO_LINHA_MACHINING_SIMPLES.matcher(bloco);
        StringBuilder semDobradicas = new StringBuilder();
        while (linhas.find()) {
            if (isFuroDobradica(lerAtributos(linhas.group()))) {
                linhas.appendReplacement(semDobradicas, "");
                removidos++;
            } else {
                linhas.appendReplacement(semDobradicas, Matcher.quoteReplacement(linhas.group()));
            }
        }
        linhas.appendTail(semDobradicas);
        bloco = semDobradicas.toString();

        // 2) insere as novas logo depois do <Machines>
        Matcher machines = PADRAO_MACHINES_ABERTO.matcher(bloco);
        if (machines.find()) {
            String recuo = machines.group(1);
            String novasLinhas = gerarLinhasDobradicas(posicoes, largura, recuo + "  ", quebraLinha);
            String antes = bloco.substring(0, machines.end());
            if (machines.group(2) == null) {
                antes += quebraLinha; // <Machines> sem quebra de linha logo depois
            }
            bloco = antes + novasLinhas + bloco.substring(machines.end());
        } else {
            Matcher machinesVazio = PADRAO_MACHINES_VAZIO.matcher(bloco);
            if (!machinesVazio.find()) {
                throw new IOException("Peça " + id + ": tag <Machines> não encontrada.");
            }
            String recuo = machinesVazio.group(1);
            String novasLinhas = gerarLinhasDobradicas(posicoes, largura, recuo + "  ", quebraLinha);
            String substituto = recuo + "<Machines>" + quebraLinha + novasLinhas + recuo + "</Machines>";
            bloco = bloco.substring(0, machinesVazio.start()) + substituto + bloco.substring(machinesVazio.end());
        }

        resumo.add(String.format("Peça %s (%s x %s): removidos %d furos antigos, %d dobradiças em X = %s",
                id, formatar(comprimento), formatar(largura), removidos, posicoes.size(), juntarPosicoes(posicoes)));
        return bloco;
    }

    /** Gera as 3 linhas (caneco + 2 furos de fixação) de cada dobradiça. */
    private String gerarLinhasDobradicas(List<Double> posicoes, double larguraPeca, String recuo, String quebraLinha) {
        double yCaneco = larguraPeca - RECUO_Y_CANECO;
        double yFixacao = larguraPeca - RECUO_Y_FIXACAO;

        StringBuilder sb = new StringBuilder();
        for (double x : posicoes) {
            sb.append(recuo).append(montarLinhaFuro(x, yCaneco, DIAMETRO_CANECO, PROFUNDIDADE_CANECO)).append(quebraLinha);
            sb.append(recuo).append(montarLinhaFuro(x - AFASTAMENTO_X_FIXACAO, yFixacao,
                    DIAMETRO_FURO_FIXACAO, PROFUNDIDADE_FURO_FIXACAO)).append(quebraLinha);
            sb.append(recuo).append(montarLinhaFuro(x + AFASTAMENTO_X_FIXACAO, yFixacao,
                    DIAMETRO_FURO_FIXACAO, PROFUNDIDADE_FURO_FIXACAO)).append(quebraLinha);
        }
        return sb.toString();
    }

    private String montarLinhaFuro(double x, double y, double diametro, double profundidade) {
        return "<Machining Type=\"2\" IsGenCode=\"2\" Face=\"" + FACE + "\""
                + " X=\"" + formatar(x) + "\""
                + " Y=\"" + formatar(y) + "\""
                + " Diameter=\"" + formatar(diametro) + "\""
                + " Depth=\"" + formatar(profundidade) + "\" />";
    }

    // =====================================================================
    // Identificação dos furos de dobradiça
    // =====================================================================

    /** Furo vertical (Type 2) na face 5 com as medidas do caneco ou do furo de fixação. */
    private boolean isFuroDobradica(Map<String, String> attr) {
        if (!"2".equals(attr.get("Type")) || !FACE.equals(attr.get("Face"))) {
            return false;
        }
        return isCaneco(attr) || isFuroFixacao(attr);
    }

    private boolean isCaneco(Map<String, String> attr) {
        return mesmoValor(attr.get("Diameter"), DIAMETRO_CANECO)
                && mesmoValor(attr.get("Depth"), PROFUNDIDADE_CANECO);
    }

    private boolean isFuroFixacao(Map<String, String> attr) {
        return mesmoValor(attr.get("Diameter"), DIAMETRO_FURO_FIXACAO)
                && mesmoValor(attr.get("Depth"), PROFUNDIDADE_FURO_FIXACAO);
    }

    private boolean mesmoValor(String texto, double esperado) {
        if (texto == null) {
            return false;
        }
        try {
            return Math.abs(Double.parseDouble(texto.trim()) - esperado) < 0.001;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // =====================================================================
    // Utilitários
    // =====================================================================

    private Map<String, String> lerAtributosDoPainel(String blocoPainel) throws IOException {
        Matcher tag = PADRAO_TAG_PAINEL.matcher(blocoPainel);
        if (!tag.find()) {
            throw new IOException("Tag <Panel> inválida.");
        }
        return lerAtributos(tag.group());
    }

    private Map<String, String> lerAtributos(String tag) {
        Map<String, String> atributos = new HashMap<>();
        Matcher m = PADRAO_ATRIBUTO.matcher(tag);
        while (m.find()) {
            atributos.put(m.group(1), m.group(2));
        }
        return atributos;
    }

    private double lerNumero(Map<String, String> atributos, String nome, String idPainel) throws IOException {
        String valor = atributos.get(nome);
        if (valor == null) {
            throw new IOException("Peça " + idPainel + ": atributo " + nome + " não encontrado.");
        }
        try {
            return Double.parseDouble(valor.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IOException("Peça " + idPainel + ": valor inválido em " + nome + " (" + valor + ").");
        }
    }

    /** 2214.0 -> "2214" | 628.5 -> "628.5" | 0.25 -> "0.25" (sem arredondar) */
    public static String formatar(double valor) {
        return BigDecimal.valueOf(valor).stripTrailingZeros().toPlainString();
    }

    public static String juntarPosicoes(List<Double> posicoes) {
        List<String> textos = new ArrayList<>();
        for (double p : posicoes) {
            textos.add(formatar(p));
        }
        return String.join(" | ", textos);
    }

    private Path fazerBackup(Path arquivo) throws IOException {
        Path pastaBackup = arquivo.toAbsolutePath().getParent().resolve(PASTA_BACKUP);
        Files.createDirectories(pastaBackup);

        String nome = arquivo.getFileName().toString();
        int ponto = nome.lastIndexOf('.');
        String base = ponto > 0 ? nome.substring(0, ponto) : nome;
        String extensao = ponto > 0 ? nome.substring(ponto) : "";

        String data = LocalDateTime.now().format(FORMATO_DATA_BACKUP);
        Path destino = pastaBackup.resolve(base + "_" + data + extensao);
        int contador = 2;
        while (Files.exists(destino)) { // atualizado mais de uma vez no mesmo segundo
            destino = pastaBackup.resolve(base + "_" + data + "_" + contador + extensao);
            contador++;
        }
        Files.copy(arquivo, destino);
        return destino;
    }

    private void gravar(Path caminho, String texto, boolean comBom) throws IOException {
        byte[] bytesTexto = texto.getBytes(StandardCharsets.UTF_8);
        byte[] saida;
        if (comBom) {
            saida = new byte[BOM_UTF8.length + bytesTexto.length];
            System.arraycopy(BOM_UTF8, 0, saida, 0, BOM_UTF8.length);
            System.arraycopy(bytesTexto, 0, saida, BOM_UTF8.length, bytesTexto.length);
        } else {
            saida = bytesTexto;
        }
        Files.write(caminho, saida);
    }

    private ConteudoArquivo lerConteudo(Path caminho) throws IOException {
        byte[] bytes = Files.readAllBytes(caminho);
        boolean temBom = bytes.length >= 3
                && bytes[0] == BOM_UTF8[0] && bytes[1] == BOM_UTF8[1] && bytes[2] == BOM_UTF8[2];
        int inicio = temBom ? 3 : 0;
        String texto = new String(bytes, inicio, bytes.length - inicio, StandardCharsets.UTF_8);
        String quebraLinha = texto.contains("\r\n") ? "\r\n" : "\n";
        return new ConteudoArquivo(texto, temBom, quebraLinha);
    }

    /** Garante que o XML está bem formado (antes de ler e antes de gravar). */
    private void validarXml(String texto) throws IOException {
        try {
            DocumentBuilderFactory fabrica = DocumentBuilderFactory.newInstance();
            fabrica.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            fabrica.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            fabrica.newDocumentBuilder().parse(new InputSource(new StringReader(texto)));
        } catch (Exception e) {
            throw new IOException("XML inválido: " + e.getMessage(), e);
        }
    }

    private record ConteudoArquivo(String texto, boolean temBom, String quebraLinha) {
    }
}
