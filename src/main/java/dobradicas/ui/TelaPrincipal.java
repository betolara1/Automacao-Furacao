package dobradicas.ui;

import dobradicas.model.ArquivoScx;
import dobradicas.model.Painel;
import dobradicas.service.CalculadoraDobradica;
import dobradicas.service.ScxService;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TelaPrincipal extends JFrame {

    private static final long serialVersionUID = 1L;

    private static final String[] COLUNAS = {
            "Arquivo", "Peça", "Comprimento (X)", "Largura (Y)", "Espessura",
            "Qtd. atual", "Qtd. nova", "Posições X", "Status"
    };
    private static final DateTimeFormatter FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final CalculadoraDobradica calculadora = new CalculadoraDobradica();
    private final ScxService scxService = new ScxService(calculadora);

    /** Arquivos abertos na tela. */
    private final List<ArquivoScx> arquivos = new ArrayList<>();
    /** Para cada linha da tabela, qual arquivo ela representa (um arquivo pode ter mais de uma peça). */
    private final List<ArquivoScx> arquivoPorLinha = new ArrayList<>();

    private final DefaultTableModel modeloTabela;
    private final JTable tabela;
    private final JTextArea areaLog;
    private final JLabel lblResumo;

    private final JButton btnAbrir = new JButton("Abrir arquivos .scx");
    private final JButton btnRemover = new JButton("Remover selecionado");
    private final JButton btnLimpar = new JButton("Limpar lista");
    private final JButton btnAtualizar = new JButton("Atualizar desenho");

    public TelaPrincipal() {
        super("Automatizar Dobradiças");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1000, 550));
        setLocationRelativeTo(null);

        modeloTabela = new DefaultTableModel(COLUNAS, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        tabela = new JTable(modeloTabela);
        tabela.setRowHeight(24);
        tabela.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tabela.getColumnModel().getColumn(0).setPreferredWidth(160);
        tabela.getColumnModel().getColumn(7).setPreferredWidth(260);
        tabela.getColumnModel().getColumn(8).setPreferredWidth(140);

        areaLog = new JTextArea();
        areaLog.setEditable(false);
        areaLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        lblResumo = new JLabel("Nenhum arquivo aberto.");

        montarLayout();
        configurarAcoes();
        configurarArrastarESoltar();
        atualizarBotoes();
    }

    private void montarLayout() {
        JPanel painelBotoes = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        painelBotoes.add(btnAbrir);
        painelBotoes.add(btnRemover);
        painelBotoes.add(btnLimpar);
        painelBotoes.add(btnAtualizar);

        btnAtualizar.setFont(btnAtualizar.getFont().deriveFont(Font.BOLD));

        JScrollPane scrollTabela = new JScrollPane(tabela);
        scrollTabela.setBorder(BorderFactory.createTitledBorder("Peças"));

        JScrollPane scrollLog = new JScrollPane(areaLog);
        scrollLog.setBorder(BorderFactory.createTitledBorder("Log"));

        JSplitPane divisao = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scrollTabela, scrollLog);
        divisao.setResizeWeight(0.65);

        JPanel rodape = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        rodape.add(lblResumo);

        JPanel conteudo = new JPanel(new BorderLayout());
        conteudo.add(painelBotoes, BorderLayout.NORTH);
        conteudo.add(divisao, BorderLayout.CENTER);
        conteudo.add(rodape, BorderLayout.SOUTH);
        setContentPane(conteudo);
    }

    private void configurarAcoes() {
        btnAbrir.addActionListener(e -> abrirArquivos());
        btnRemover.addActionListener(e -> removerSelecionado());
        btnLimpar.addActionListener(e -> limparLista());
        btnAtualizar.addActionListener(e -> atualizarDesenhos());
        tabela.getSelectionModel().addListSelectionListener(e -> atualizarBotoes());
    }

    // =====================================================================
    // Ações dos botões
    // =====================================================================

    private void abrirArquivos() {
        List<Path> escolhidos = new SeletorArquivosDialog(this).escolher();
        adicionarArquivos(escolhidos);
    }

    /** Usado pelo botão "Abrir" e também quando arrasta arquivos para a tela. */
    private void adicionarArquivos(List<Path> caminhos) {
        if (caminhos.isEmpty()) {
            return;
        }

        int abertos = 0;
        List<String> erros = new ArrayList<>();
        for (Path caminhoOriginal : caminhos) {
            Path caminho = caminhoOriginal.toAbsolutePath();
            String nome = caminho.getFileName().toString();

            if (!nome.toLowerCase(Locale.ROOT).endsWith(".scx")) {
                log("Ignorado (não é .scx): " + nome);
                continue;
            }
            if (jaEstaNaLista(caminho)) {
                log("Já está na lista: " + nome);
                continue;
            }
            try {
                ArquivoScx scx = scxService.ler(caminho);
                arquivos.add(scx);
                abertos++;
                log("Aberto: " + nome + " (" + scx.getPaineis().size() + " peça(s))");
            } catch (Exception ex) {
                erros.add(nome + ": " + ex.getMessage());
                log("ERRO ao abrir " + nome + ": " + ex.getMessage());
            }
        }
        atualizarTabela();

        // uma mensagem só no final, em vez de uma por arquivo
        if (!erros.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    abertos + " arquivo(s) aberto(s), " + erros.size() + " com erro:\n\n"
                            + String.join("\n", erros.subList(0, Math.min(10, erros.size())))
                            + (erros.size() > 10 ? "\n... (veja o log)" : ""),
                    "Erro ao abrir", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Permite arrastar arquivos .scx do Windows Explorer direto para a tela. */
    private void configurarArrastarESoltar() {
        TransferHandler handler = new TransferHandler() {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean canImport(TransferSupport suporte) {
                return suporte.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            public boolean importData(TransferSupport suporte) {
                if (!canImport(suporte)) {
                    return false;
                }
                try {
                    @SuppressWarnings("unchecked")
                    List<File> soltos = (List<File>) suporte.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    List<Path> caminhos = new ArrayList<>();
                    for (File f : soltos) {
                        caminhos.add(f.toPath());
                    }
                    adicionarArquivos(caminhos);
                    return true;
                } catch (Exception ex) {
                    log("ERRO ao soltar arquivos: " + ex.getMessage());
                    return false;
                }
            }
        };
        tabela.setTransferHandler(handler);
        tabela.setFillsViewportHeight(true); // aceita soltar mesmo com a tabela vazia
        ((JComponent) getContentPane()).setTransferHandler(handler);
    }

    private void removerSelecionado() {
        int linha = tabela.getSelectedRow();
        if (linha < 0) {
            return;
        }
        ArquivoScx arquivo = arquivoPorLinha.get(linha);
        arquivos.remove(arquivo);
        log("Removido da lista: " + arquivo.getNome());
        atualizarTabela();
    }

    private void limparLista() {
        arquivos.clear();
        atualizarTabela();
        log("Lista limpa.");
    }

    private void atualizarDesenhos() {
        if (arquivos.isEmpty()) {
            return;
        }

        int resposta = JOptionPane.showConfirmDialog(this,
                "Atualizar as dobradiças de " + arquivos.size() + " arquivo(s)?\n"
                        + "O arquivo original será sobrescrito (uma cópia vai para a pasta \"backup\").",
                "Confirmar", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (resposta != JOptionPane.YES_OPTION) {
            return;
        }

        setProcessando(true);
        List<ArquivoScx> paraProcessar = new ArrayList<>(arquivos);

        // Roda fora da thread da tela para não travar a interface
        new SwingWorker<Integer, String>() {
            @Override
            protected Integer doInBackground() {
                int erros = 0;
                for (ArquivoScx arquivo : paraProcessar) {
                    publish("Atualizando " + arquivo.getNome() + "...");
                    try {
                        List<String> resumo = scxService.atualizar(arquivo);
                        for (String linha : resumo) {
                            publish("   " + linha);
                        }
                        recarregar(arquivo, "Atualizado");
                    } catch (Exception ex) {
                        erros++;
                        arquivo.setStatus("Erro");
                        publish("   ERRO: " + ex.getMessage());
                    }
                }
                return erros;
            }

            @Override
            protected void process(List<String> mensagens) {
                for (String msg : mensagens) {
                    log(msg);
                }
            }

            @Override
            protected void done() {
                setProcessando(false);
                atualizarTabela();
                try {
                    int erros = get();
                    if (erros == 0) {
                        log("Concluído sem erros.");
                        JOptionPane.showMessageDialog(TelaPrincipal.this,
                                "Desenho(s) atualizado(s) com sucesso!", "Pronto",
                                JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        log("Concluído com " + erros + " erro(s).");
                        JOptionPane.showMessageDialog(TelaPrincipal.this,
                                erros + " arquivo(s) com erro. Veja o log.", "Atenção",
                                JOptionPane.WARNING_MESSAGE);
                    }
                } catch (Exception ex) {
                    log("ERRO inesperado: " + ex.getMessage());
                }
            }
        }.execute();
    }

    /** Lê o arquivo de novo depois de gravado, para a tabela mostrar a situação real. */
    private void recarregar(ArquivoScx antigo, String status) {
        try {
            ArquivoScx novo = scxService.ler(antigo.getCaminho());
            novo.setStatus(status);
            synchronized (arquivos) {
                int indice = arquivos.indexOf(antigo);
                if (indice >= 0) {
                    arquivos.set(indice, novo);
                }
            }
        } catch (Exception ex) {
            antigo.setStatus(status + " (falha ao reler)");
        }
    }

    // =====================================================================
    // Tela
    // =====================================================================

    private void atualizarTabela() {
        modeloTabela.setRowCount(0);
        arquivoPorLinha.clear();

        int totalPecas = 0;
        for (ArquivoScx arquivo : arquivos) {
            for (Painel painel : arquivo.getPaineis()) {
                modeloTabela.addRow(new Object[]{
                        arquivo.getNome(),
                        painel.getId(),
                        ScxService.formatar(painel.getComprimento()),
                        ScxService.formatar(painel.getLargura()),
                        ScxService.formatar(painel.getEspessura()),
                        painel.getDobradicasExistentes(),
                        textoQuantidade(painel),
                        textoPosicoes(painel),
                        arquivo.getStatus()
                });
                arquivoPorLinha.add(arquivo);
                totalPecas++;
            }
        }

        if (arquivos.isEmpty()) {
            lblResumo.setText("Nenhum arquivo aberto.");
        } else {
            lblResumo.setText(arquivos.size() + " arquivo(s), " + totalPecas + " peça(s).");
        }
        atualizarBotoes();
    }

    private String textoQuantidade(Painel painel) {
        try {
            return String.valueOf(calculadora.calcularPosicoes(painel.getComprimento()).size());
        } catch (IllegalArgumentException e) {
            return "-";
        }
    }

    private String textoPosicoes(Painel painel) {
        try {
            return ScxService.juntarPosicoes(calculadora.calcularPosicoes(painel.getComprimento()));
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    private boolean jaEstaNaLista(Path caminho) {
        for (ArquivoScx arquivo : arquivos) {
            if (arquivo.getCaminho().equals(caminho)) {
                return true;
            }
        }
        return false;
    }

    private void setProcessando(boolean processando) {
        btnAbrir.setEnabled(!processando);
        btnRemover.setEnabled(!processando);
        btnLimpar.setEnabled(!processando);
        btnAtualizar.setEnabled(!processando);
        if (!processando) {
            atualizarBotoes();
        }
    }

    private void atualizarBotoes() {
        boolean temArquivos = !arquivos.isEmpty();
        btnAtualizar.setEnabled(temArquivos);
        btnLimpar.setEnabled(temArquivos);
        btnRemover.setEnabled(tabela.getSelectedRow() >= 0);
    }

    private void log(String mensagem) {
        areaLog.append("[" + LocalTime.now().format(FORMATO_HORA) + "] " + mensagem + "\n");
        areaLog.setCaretPosition(areaLog.getDocument().getLength());
    }
}
