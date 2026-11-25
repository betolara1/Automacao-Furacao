import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Fica rodando direto e aplica as furações de dobradiça em todos os .scx da pasta POR.
 *
 * Funcionamento:
 *  1. Ao iniciar, analisa todos os .scx que já estão na pasta.
 *  2. Depois, a cada 5 minutos, analisa de novo. Só abre os arquivos novos ou que
 *     foram alterados desde a última vez (cache por data de modificação + tamanho).
 *  3. Se as dobradiças do arquivo já estiverem certas, não grava nada.
 *  4. Se precisar alterar: copia o original para POR\backup (com data e hora no nome)
 *     e grava o arquivo novo por cima.
 *
 * Regras (as mesmas do programa Automatizar-Dobradicas):
 *  - LARGURA_TOTAL = Length da peça (eixo X)
 *  - até 900 = 2 | >900 até 1600 = 3 | >1600 até 2200 = 4 | >2200 = 5
 *  - 1ª em 100, última em LARGURA_TOTAL - 100, as do meio divididas igualmente
 *  - caneco Ø35 prof. 12 em Y = Width - 22
 *  - 2 furos Ø5.1 prof. 0.25 em X - 24 e X + 24, Y = Width - 28
 *
 * Como rodar (Java 21):
 *     java dobradicas_automaticas_por.java
 *     java dobradicas_automaticas_por.java "C:\outra\pasta"   (opcional: outra pasta)
 *
 * Para parar: Ctrl + C (ou finalizar a tarefa no Agendador).
 */
public class dobradicas_automaticas_por {

    // ======================= CONFIGURAÇÃO =======================

    private static final String PASTA_PADRAO =
            "\\\\192.168.1.10\\DatabaseFolder\\Programas\\Nanxing\\NCB612\\Machine1\\Setup\\POR";

    /** Tempo entre uma varredura e outra. */
    private static final long INTERVALO_MINUTOS = 5;

    /**
     * Arquivo modificado há menos que isso é ignorado nesta rodada
     * (pode ainda estar sendo gravado pelo programa de desenho). Pega na próxima.
     */
    private static final long SEGUNDOS_PARA_ARQUIVO_ESTABILIZAR = 30;

    private static final String NOME_PASTA_BACKUP = "backup";
    private static final Path ARQUIVO_LOG = Path.of("dobradicas_automaticas_por.log");

    // ======================= REGRAS DA DOBRADIÇA =======================

    private static final double DISTANCIA_PONTA = 100.0;
    private static final double LIMITE_3_DOBRADICAS = 900.0;
    private static final double LIMITE_4_DOBRADICAS = 1600.0;
    private static final double LIMITE_5_DOBRADICAS = 2200.0;

    private static final double DIAMETRO_CANECO = 35.0;
    private static final double PROFUNDIDADE_CANECO = 12.0;
    private static final double DIAMETRO_FURO_FIXACAO = 5.1;
    private static final double PROFUNDIDADE_FURO_FIXACAO = 0.25;
    private static final double RECUO_Y_CANECO = 22.0;
    private static final double RECUO_Y_FIXACAO = 28.0;
    private static final double AFASTAMENTO_X_FIXACAO = 24.0;
    private static final String FACE = "5";

    // ======================= REGEX =======================

    private static final Pattern PADRAO_BLOCO_PAINEL =
            Pattern.compile("<Panel\\b[^>]*>.*?</Panel>", Pattern.DOTALL);
    private static final Pattern PADRAO_TAG_PAINEL = Pattern.compile("<Panel\\b[^>]*>");
    private static final Pattern PADRAO_ATRIBUTO = Pattern.compile("([\\w:]+)\\s*=\\s*\"([^\"]*)\"");
    /** Linha inteira de um <Machining ... /> sem filhos (as fresagens têm filhos e não entram). */
    private static final Pattern PADRAO_LINHA_MACHINING_SIMPLES =
            Pattern.compile("[ \\t]*<Machining\\b[^>]*?/>[ \\t]*(\\r?\\n)?");
    private static final Pattern PADRAO_MACHINES_ABERTO = Pattern.compile("([ \\t]*)<Machines>(\\r?\\n)?");
    private static final Pattern PADRAO_MACHINES_VAZIO = Pattern.compile("([ \\t]*)<Machines\\s*/>");

    private static final byte[] BOM_UTF8 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final DateTimeFormatter FORMATO_LOG = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final DateTimeFormatter FORMATO_BACKUP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * Cache: nome do arquivo -> "dataModificacao|tamanho" da última vez que foi analisado.
     * Se o arquivo não mudou, não abre de novo.
     */
    private static final Map<String, String> cache = new HashMap<>();

    // =====================================================================

    public static void main(String[] args) {
        Path pasta = Path.of(args.length > 0 ? args[0] : PASTA_PADRAO);

        log("==================================================");
        log("Iniciado. Pasta: " + pasta);
        log("Varredura a cada " + INTERVALO_MINUTOS + " minuto(s).");

        while (true) {
            try {
                varrerPasta(pasta);
            } catch (Exception e) {
                // nunca deixa o loop morrer (ex.: servidor fora do ar por um tempo)
                log("ERRO na varredura: " + e.getMessage());
            }

            try {
                Thread.sleep(INTERVALO_MINUTOS * 60 * 1000);
            } catch (InterruptedException e) {
                log("Interrompido. Encerrando.");
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void varrerPasta(Path pasta) throws IOException {
        if (!Files.isDirectory(pasta)) {
            log("Pasta não encontrada ou sem acesso: " + pasta);
            return;
        }

        int analisados = 0;
        int alterados = 0;
        int erros = 0;
        long agora = System.currentTimeMillis();

        // só os arquivos da própria pasta (a subpasta backup não entra)
        try (DirectoryStream<Path> arquivos = Files.newDirectoryStream(pasta, "*.{scx,SCX,Scx}")) {
            for (Path arquivo : arquivos) {
                if (!Files.isRegularFile(arquivo)) {
                    continue;
                }

                String nome = arquivo.getFileName().toString();
                long modificado = Files.getLastModifiedTime(arquivo).toMillis();
                long tamanho = Files.size(arquivo);
                String assinatura = modificado + "|" + tamanho;

                if (assinatura.equals(cache.get(nome))) {
                    continue; // já analisado e não mudou
                }
                if (agora - modificado < SEGUNDOS_PARA_ARQUIVO_ESTABILIZAR * 1000) {
                    continue; // acabou de chegar, pode estar sendo gravado
                }

                analisados++;
                try {
                    if (processarArquivo(arquivo, pasta)) {
                        alterados++;
                    }
                } catch (Exception e) {
                    erros++;
                    log("ERRO em " + nome + ": " + e.getMessage());
                }

                // guarda o estado ATUAL (depois de gravar) para não analisar de novo à toa.
                // Se deu erro, só tenta de novo quando o arquivo for alterado.
                try {
                    cache.put(nome, Files.getLastModifiedTime(arquivo).toMillis() + "|" + Files.size(arquivo));
                } catch (IOException e) {
                    cache.remove(nome); // arquivo sumiu no meio do caminho
                }
            }
        }

        if (analisados > 0) {
            log("Varredura: " + analisados + " analisado(s), " + alterados + " alterado(s), " + erros + " erro(s).");
        }
    }

    /**
     * @return true se o arquivo foi alterado, false se já estava certo
     */
    private static boolean processarArquivo(Path arquivo, Path pasta) throws IOException {
        byte[] bytes = Files.readAllBytes(arquivo);
        boolean temBom = bytes.length >= 3
                && bytes[0] == BOM_UTF8[0] && bytes[1] == BOM_UTF8[1] && bytes[2] == BOM_UTF8[2];
        int inicio = temBom ? 3 : 0;
        String textoOriginal = new String(bytes, inicio, bytes.length - inicio, StandardCharsets.UTF_8);
        String quebraLinha = textoOriginal.contains("\r\n") ? "\r\n" : "\n";

        validarXml(textoOriginal);

        List<String> resumo = new ArrayList<>();
        Matcher blocos = PADRAO_BLOCO_PAINEL.matcher(textoOriginal);
        StringBuilder novoTexto = new StringBuilder();
        int quantidadePaineis = 0;
        while (blocos.find()) {
            quantidadePaineis++;
            String blocoNovo = processarPainel(blocos.group(), quebraLinha, resumo);
            blocos.appendReplacement(novoTexto, Matcher.quoteReplacement(blocoNovo));
        }
        blocos.appendTail(novoTexto);

        if (quantidadePaineis == 0) {
            throw new IOException("nenhuma peça (<Panel>) encontrada.");
        }

        String textoFinal = novoTexto.toString();
        if (textoFinal.equals(textoOriginal)) {
            return false; // dobradiças já estão certas, não grava nada
        }

        validarXml(textoFinal);

        Path backup = fazerBackup(arquivo, pasta);
        gravarComSeguranca(arquivo, textoFinal, temBom);

        log("ALTERADO " + arquivo.getFileName() + " (backup: " + backup.getFileName() + ")");
        for (String linha : resumo) {
            log("    " + linha);
        }
        return true;
    }

    private static String processarPainel(String bloco, String quebraLinha, List<String> resumo) throws IOException {
        Map<String, String> atributosPainel = lerAtributos(primeiraTagPainel(bloco));
        String id = atributosPainel.getOrDefault("ID", "(sem ID)");
        double comprimento = lerNumero(atributosPainel, "Length", id);
        double largura = lerNumero(atributosPainel, "Width", id);

        List<Double> posicoes = calcularPosicoes(comprimento, id);

        // 1) remove as furações de dobradiça existentes
        Matcher linhas = PADRAO_LINHA_MACHINING_SIMPLES.matcher(bloco);
        StringBuilder semDobradicas = new StringBuilder();
        while (linhas.find()) {
            if (isFuroDobradica(lerAtributos(linhas.group()))) {
                linhas.appendReplacement(semDobradicas, "");
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
                antes += quebraLinha;
            }
            bloco = antes + novasLinhas + bloco.substring(machines.end());
        } else {
            Matcher vazio = PADRAO_MACHINES_VAZIO.matcher(bloco);
            if (!vazio.find()) {
                throw new IOException("peça " + id + ": tag <Machines> não encontrada.");
            }
            String recuo = vazio.group(1);
            String novasLinhas = gerarLinhasDobradicas(posicoes, largura, recuo + "  ", quebraLinha);
            String substituto = recuo + "<Machines>" + quebraLinha + novasLinhas + recuo + "</Machines>";
            bloco = bloco.substring(0, vazio.start()) + substituto + bloco.substring(vazio.end());
        }

        resumo.add("Peça " + id + " (" + formatar(comprimento) + " x " + formatar(largura) + "): "
                + posicoes.size() + " dobradiças em X = " + juntar(posicoes));
        return bloco;
    }

    // ======================= CÁLCULO =======================

    private static List<Double> calcularPosicoes(double larguraTotal, String id) throws IOException {
        if (larguraTotal <= DISTANCIA_PONTA * 2) {
            throw new IOException("peça " + id + " muito pequena para dobradiça (" + formatar(larguraTotal) + "mm).");
        }

        int quantidade;
        if (larguraTotal <= LIMITE_3_DOBRADICAS) {
            quantidade = 2;
        } else if (larguraTotal <= LIMITE_4_DOBRADICAS) {
            quantidade = 3;
        } else if (larguraTotal <= LIMITE_5_DOBRADICAS) {
            quantidade = 4;
        } else {
            quantidade = 5;
        }

        double passo = (larguraTotal - DISTANCIA_PONTA * 2) / (quantidade - 1);
        List<Double> posicoes = new ArrayList<>();
        for (int i = 0; i < quantidade; i++) {
            double x = passo * i + DISTANCIA_PONTA;
            posicoes.add(Math.round(x * 10.0) / 10.0); // 1 casa decimal
        }
        return posicoes;
    }

    private static String gerarLinhasDobradicas(List<Double> posicoes, double larguraPeca, String recuo, String quebra) {
        double yCaneco = larguraPeca - RECUO_Y_CANECO;
        double yFixacao = larguraPeca - RECUO_Y_FIXACAO;

        StringBuilder sb = new StringBuilder();
        for (double x : posicoes) {
            sb.append(recuo).append(linhaFuro(x, yCaneco, DIAMETRO_CANECO, PROFUNDIDADE_CANECO)).append(quebra);
            sb.append(recuo).append(linhaFuro(x - AFASTAMENTO_X_FIXACAO, yFixacao,
                    DIAMETRO_FURO_FIXACAO, PROFUNDIDADE_FURO_FIXACAO)).append(quebra);
            sb.append(recuo).append(linhaFuro(x + AFASTAMENTO_X_FIXACAO, yFixacao,
                    DIAMETRO_FURO_FIXACAO, PROFUNDIDADE_FURO_FIXACAO)).append(quebra);
        }
        return sb.toString();
    }

    private static String linhaFuro(double x, double y, double diametro, double profundidade) {
        return "<Machining Type=\"2\" IsGenCode=\"2\" Face=\"" + FACE + "\""
                + " X=\"" + formatar(x) + "\""
                + " Y=\"" + formatar(y) + "\""
                + " Diameter=\"" + formatar(diametro) + "\""
                + " Depth=\"" + formatar(profundidade) + "\" />";
    }

    // ======================= IDENTIFICAÇÃO DOS FUROS =======================

    /** Furo vertical (Type 2) na face 5 com medida de caneco (Ø35/12) ou de fixação (Ø5.1/0.25). */
    private static boolean isFuroDobradica(Map<String, String> attr) {
        if (!"2".equals(attr.get("Type")) || !FACE.equals(attr.get("Face"))) {
            return false;
        }
        boolean caneco = mesmoValor(attr.get("Diameter"), DIAMETRO_CANECO)
                && mesmoValor(attr.get("Depth"), PROFUNDIDADE_CANECO);
        boolean fixacao = mesmoValor(attr.get("Diameter"), DIAMETRO_FURO_FIXACAO)
                && mesmoValor(attr.get("Depth"), PROFUNDIDADE_FURO_FIXACAO);
        return caneco || fixacao;
    }

    private static boolean mesmoValor(String texto, double esperado) {
        if (texto == null) {
            return false;
        }
        try {
            return Math.abs(Double.parseDouble(texto.trim()) - esperado) < 0.001;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ======================= ARQUIVOS =======================

    private static Path fazerBackup(Path arquivo, Path pasta) throws IOException {
        Path pastaBackup = pasta.resolve(NOME_PASTA_BACKUP);
        Files.createDirectories(pastaBackup);

        String nome = arquivo.getFileName().toString();
        int ponto = nome.lastIndexOf('.');
        String base = ponto > 0 ? nome.substring(0, ponto) : nome;
        String extensao = ponto > 0 ? nome.substring(ponto) : "";
        String data = LocalDateTime.now().format(FORMATO_BACKUP);

        Path destino = pastaBackup.resolve(base + "_" + data + extensao);
        int contador = 2;
        while (Files.exists(destino)) {
            destino = pastaBackup.resolve(base + "_" + data + "_" + contador + extensao);
            contador++;
        }
        Files.copy(arquivo, destino, StandardCopyOption.COPY_ATTRIBUTES);
        return destino;
    }

    /**
     * Grava primeiro num arquivo temporário (sem extensão .scx, para a máquina não pegar)
     * e depois troca pelo original. Assim a máquina nunca lê um arquivo pela metade.
     */
    private static void gravarComSeguranca(Path arquivo, String texto, boolean comBom) throws IOException {
        byte[] conteudo = texto.getBytes(StandardCharsets.UTF_8);
        Path temporario = arquivo.resolveSibling(arquivo.getFileName() + ".tmp");

        try {
            if (comBom) {
                Files.write(temporario, BOM_UTF8);
                Files.write(temporario, conteudo, StandardOpenOption.APPEND);
            } else {
                Files.write(temporario, conteudo);
            }

            try {
                Files.move(temporario, arquivo, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // alguns compartilhamentos não aceitam ATOMIC_MOVE
                Files.move(temporario, arquivo, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporario);
        }
    }

    private static void validarXml(String texto) throws IOException {
        try {
            DocumentBuilderFactory fabrica = DocumentBuilderFactory.newInstance();
            fabrica.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            fabrica.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder leitor = fabrica.newDocumentBuilder();
            leitor.setErrorHandler(new DefaultHandler()); // não imprime "[Fatal Error]" no console
            leitor.parse(new InputSource(new StringReader(texto)));
        } catch (Exception e) {
            throw new IOException("XML inválido: " + e.getMessage(), e);
        }
    }

    // ======================= UTILITÁRIOS =======================

    private static String primeiraTagPainel(String bloco) throws IOException {
        Matcher tag = PADRAO_TAG_PAINEL.matcher(bloco);
        if (!tag.find()) {
            throw new IOException("tag <Panel> inválida.");
        }
        return tag.group();
    }

    private static Map<String, String> lerAtributos(String tag) {
        Map<String, String> atributos = new HashMap<>();
        Matcher m = PADRAO_ATRIBUTO.matcher(tag);
        while (m.find()) {
            atributos.put(m.group(1), m.group(2));
        }
        return atributos;
    }

    private static double lerNumero(Map<String, String> atributos, String nome, String id) throws IOException {
        String valor = atributos.get(nome);
        if (valor == null) {
            throw new IOException("peça " + id + ": atributo " + nome + " não encontrado.");
        }
        try {
            return Double.parseDouble(valor.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IOException("peça " + id + ": valor inválido em " + nome + " (" + valor + ").");
        }
    }

    /** 2214.0 -> "2214" | 628.5 -> "628.5" | 0.25 -> "0.25" */
    private static String formatar(double valor) {
        return BigDecimal.valueOf(valor).stripTrailingZeros().toPlainString();
    }

    private static String juntar(List<Double> valores) {
        List<String> textos = new ArrayList<>();
        for (double v : valores) {
            textos.add(formatar(v));
        }
        return String.join(" | ", textos);
    }

    /** Escreve no console e no arquivo de log. */
    private static void log(String mensagem) {
        String linha = "[" + LocalDateTime.now().format(FORMATO_LOG) + "] " + mensagem;
        System.out.println(linha);
        try {
            Files.writeString(ARQUIVO_LOG, linha + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("(não foi possível gravar no log: " + e.getMessage() + ")");
        }
    }
}
