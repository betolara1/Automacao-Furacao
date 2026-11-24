package dobradicas.ui;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

/**
 * Janela para escolher os .scx de uma pasta com busca pelo nome.
 *
 * - Lista só os .scx da pasta (não entra em subpastas, então a pasta "backup" fica de fora).
 * - A busca filtra enquanto digita. Pode digitar vários códigos separados por espaço,
 *   vírgula ou ponto e vírgula (ex.: "5792 5793 5800").
 * - Enter na busca adiciona o que estiver filtrado na lista de selecionados e limpa a busca.
 * - Duplo clique em um arquivo também adiciona.
 * - A última pasta usada fica salva para a próxima vez.
 */
public class SeletorArquivosDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private static final String CHAVE_ULTIMA_PASTA = "ultimaPasta";
    private static final int LIMITE_SEM_CONFIRMAR = 50;

    private final transient Preferences preferencias = Preferences.userNodeForPackage(SeletorArquivosDialog.class);

    /** Todos os .scx da pasta atual (só o nome do arquivo). */
    private final List<String> todosArquivos = new ArrayList<>();
    /** Os que já foram escolhidos, na ordem em que foram adicionados. */
    private final Set<String> selecionados = new LinkedHashSet<>();

    private final DefaultListModel<String> modeloResultado = new DefaultListModel<>();
    private final DefaultListModel<String> modeloSelecionados = new DefaultListModel<>();
    private final JList<String> listaResultado = new JList<>(modeloResultado);
    private final JList<String> listaSelecionados = new JList<>(modeloSelecionados);

    private final JTextField txtPasta = new JTextField();
    private final JTextField txtBusca = new JTextField();
    private final JLabel lblResultado = new JLabel(" ");
    private final JLabel lblSelecionados = new JLabel(" ");
    private final JButton btnOk = new JButton("Abrir");

    private Path pastaAtual;
    private List<Path> resultadoFinal = Collections.emptyList();

    public SeletorArquivosDialog(JFrame dono) {
        super(dono, "Selecionar arquivos .scx", true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(820, 560));
        setLocationRelativeTo(dono);

        montarLayout();
        configurarAcoes();

        String ultima = preferencias.get(CHAVE_ULTIMA_PASTA, null);
        if (ultima != null && Files.isDirectory(Path.of(ultima))) {
            carregarPasta(Path.of(ultima));
        }
        atualizarContadores();
    }

    /** Abre a janela e devolve os arquivos escolhidos (lista vazia se cancelar). */
    public List<Path> escolher() {
        txtBusca.requestFocusInWindow();
        setVisible(true); // modal: só continua quando a janela fechar
        return resultadoFinal;
    }

    // =====================================================================
    // Layout
    // =====================================================================

    private void montarLayout() {
        // --- Pasta ---
        txtPasta.setEditable(false);
        JButton btnTrocarPasta = new JButton("Trocar pasta...");
        JButton btnRecarregar = new JButton("Recarregar");
        btnTrocarPasta.addActionListener(e -> escolherPasta());
        btnRecarregar.addActionListener(e -> {
            if (pastaAtual != null) {
                carregarPasta(pastaAtual);
            }
        });

        JPanel painelPasta = new JPanel(new BorderLayout(6, 0));
        painelPasta.add(new JLabel("Pasta:"), BorderLayout.WEST);
        painelPasta.add(txtPasta, BorderLayout.CENTER);
        JPanel botoesPasta = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        botoesPasta.add(btnTrocarPasta);
        botoesPasta.add(btnRecarregar);
        painelPasta.add(botoesPasta, BorderLayout.EAST);

        // --- Busca ---
        JPanel painelBusca = new JPanel(new BorderLayout(6, 0));
        painelBusca.add(new JLabel("Buscar:"), BorderLayout.WEST);
        painelBusca.add(txtBusca, BorderLayout.CENTER);
        JLabel dica = new JLabel("Vários códigos: separe por espaço ou vírgula. Enter adiciona os encontrados.");
        dica.setFont(dica.getFont().deriveFont(11f));

        JPanel topo = new JPanel();
        topo.setLayout(new BoxLayout(topo, BoxLayout.Y_AXIS));
        topo.add(painelPasta);
        topo.add(Box.createVerticalStrut(8));
        topo.add(painelBusca);
        topo.add(Box.createVerticalStrut(2));
        JPanel linhaDica = new JPanel(new FlowLayout(FlowLayout.LEFT, 52, 0));
        linhaDica.add(dica);
        topo.add(linhaDica);

        // --- Listas ---
        listaResultado.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        listaSelecionados.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        JPanel painelResultado = new JPanel(new BorderLayout(0, 4));
        painelResultado.setBorder(BorderFactory.createTitledBorder("Arquivos da pasta"));
        painelResultado.add(new JScrollPane(listaResultado), BorderLayout.CENTER);
        painelResultado.add(lblResultado, BorderLayout.SOUTH);

        JPanel painelSelecionados = new JPanel(new BorderLayout(0, 4));
        painelSelecionados.setBorder(BorderFactory.createTitledBorder("Selecionados"));
        painelSelecionados.add(new JScrollPane(listaSelecionados), BorderLayout.CENTER);
        painelSelecionados.add(lblSelecionados, BorderLayout.SOUTH);

        JButton btnAdicionar = new JButton("Adicionar  >");
        JButton btnAdicionarTodos = new JButton("Todos da busca  >>");
        JButton btnRemover = new JButton("<  Remover");
        JButton btnLimpar = new JButton("<<  Limpar");
        btnAdicionar.addActionListener(e -> adicionarMarcados());
        btnAdicionarTodos.addActionListener(e -> adicionarTodosDaBusca());
        btnRemover.addActionListener(e -> removerMarcados());
        btnLimpar.addActionListener(e -> {
            selecionados.clear();
            atualizarListaSelecionados();
        });

        JPanel botoesMeio = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 6, 4, 6);
        botoesMeio.add(btnAdicionar, c);
        botoesMeio.add(btnAdicionarTodos, c);
        c.insets = new Insets(20, 6, 4, 6);
        botoesMeio.add(btnRemover, c);
        c.insets = new Insets(4, 6, 4, 6);
        botoesMeio.add(btnLimpar, c);

        JPanel centro = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.BOTH;
        g.weighty = 1;
        g.gridx = 0;
        g.weightx = 1;
        centro.add(painelResultado, g);
        g.gridx = 1;
        g.weightx = 0;
        centro.add(botoesMeio, g);
        g.gridx = 2;
        g.weightx = 1;
        centro.add(painelSelecionados, g);

        // --- Rodapé ---
        JButton btnCancelar = new JButton("Cancelar");
        btnCancelar.addActionListener(e -> dispose());
        btnOk.addActionListener(e -> confirmar());

        JPanel rodape = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rodape.add(btnOk);
        rodape.add(btnCancelar);

        JPanel conteudo = new JPanel(new BorderLayout(0, 10));
        conteudo.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        conteudo.add(topo, BorderLayout.NORTH);
        conteudo.add(centro, BorderLayout.CENTER);
        conteudo.add(rodape, BorderLayout.SOUTH);
        setContentPane(conteudo);
    }

    private void configurarAcoes() {
        // filtra enquanto digita
        txtBusca.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                filtrar();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                filtrar();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                filtrar();
            }
        });

        // Enter na busca: adiciona os encontrados
        txtBusca.addActionListener(e -> adicionarTodosDaBusca());

        // seta pra baixo na busca: vai para a lista
        txtBusca.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "irParaLista");
        txtBusca.getActionMap().put("irParaLista", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (!modeloResultado.isEmpty()) {
                    listaResultado.requestFocusInWindow();
                    if (listaResultado.isSelectionEmpty()) {
                        listaResultado.setSelectedIndex(0);
                    }
                }
            }
        });

        // Enter na lista da esquerda: adiciona os marcados
        listaResultado.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "adicionar");
        listaResultado.getActionMap().put("adicionar", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                adicionarMarcados();
            }
        });

        // Delete na lista da direita: remove
        listaSelecionados.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "remover");
        listaSelecionados.getActionMap().put("remover", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                removerMarcados();
            }
        });

        // duplo clique
        listaResultado.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    adicionarMarcados();
                }
            }
        });
        listaSelecionados.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    removerMarcados();
                }
            }
        });

        // Esc fecha a janela
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    // =====================================================================
    // Pasta
    // =====================================================================

    private void escolherPasta() {
        JFileChooser seletor = new JFileChooser(pastaAtual != null ? pastaAtual.toFile() : null);
        seletor.setDialogTitle("Escolha a pasta dos desenhos");
        seletor.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (seletor.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            carregarPasta(seletor.getSelectedFile().toPath());
        }
    }

    private void carregarPasta(Path pasta) {
        List<String> encontrados = new ArrayList<>();
        try (Stream<Path> arquivos = Files.list(pasta)) {
            arquivos.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(nome -> nome.toLowerCase(Locale.ROOT).endsWith(".scx"))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(encontrados::add);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Não foi possível ler a pasta:\n" + e.getMessage(),
                    "Erro", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // trocou de pasta: os selecionados da pasta anterior não valem mais
        if (pastaAtual != null && !pastaAtual.equals(pasta)) {
            selecionados.clear();
        }

        pastaAtual = pasta;
        todosArquivos.clear();
        todosArquivos.addAll(encontrados);
        selecionados.retainAll(todosArquivos);

        txtPasta.setText(pasta.toString());
        preferencias.put(CHAVE_ULTIMA_PASTA, pasta.toString());

        filtrar();
        atualizarListaSelecionados();
    }

    // =====================================================================
    // Busca e seleção
    // =====================================================================

    private void filtrar() {
        List<String> termos = lerTermosBusca();
        modeloResultado.clear();

        List<String> filtrados = new ArrayList<>();
        for (String nome : todosArquivos) {
            if (combina(nome, termos)) {
                filtrados.add(nome);
            }
        }
        modeloResultado.addAll(filtrados);

        // facilita: com vários códigos (ou um único resultado) já deixa marcado o que foi encontrado
        if (!filtrados.isEmpty() && (termos.size() > 1 || filtrados.size() == 1)) {
            listaResultado.setSelectionInterval(0, filtrados.size() - 1);
        }
        atualizarContadores();
    }

    /** Separa o texto da busca em termos (espaço, vírgula, ponto e vírgula ou quebra de linha). */
    private List<String> lerTermosBusca() {
        List<String> termos = new ArrayList<>();
        for (String parte : txtBusca.getText().trim().split("[\\s,;]+")) {
            if (!parte.isBlank()) {
                termos.add(parte.toLowerCase(Locale.ROOT));
            }
        }
        return termos;
    }

    /** O arquivo aparece se o nome contém qualquer um dos termos digitados. */
    private boolean combina(String nomeArquivo, List<String> termos) {
        if (termos.isEmpty()) {
            return true;
        }
        String nome = nomeArquivo.toLowerCase(Locale.ROOT);
        for (String termo : termos) {
            if (nome.contains(termo)) {
                return true;
            }
        }
        return false;
    }

    private void adicionarMarcados() {
        List<String> marcados = listaResultado.getSelectedValuesList();
        if (marcados.isEmpty() && modeloResultado.size() == 1) {
            marcados = List.of(modeloResultado.get(0));
        }
        if (marcados.isEmpty()) {
            return;
        }
        selecionados.addAll(marcados);
        atualizarListaSelecionados();
    }

    private void adicionarTodosDaBusca() {
        if (modeloResultado.isEmpty()) {
            return;
        }
        if (modeloResultado.size() > LIMITE_SEM_CONFIRMAR) {
            int resp = JOptionPane.showConfirmDialog(this,
                    "Adicionar " + modeloResultado.size() + " arquivos?", "Confirmar",
                    JOptionPane.YES_NO_OPTION);
            if (resp != JOptionPane.YES_OPTION) {
                return;
            }
        }
        for (int i = 0; i < modeloResultado.size(); i++) {
            selecionados.add(modeloResultado.get(i));
        }
        atualizarListaSelecionados();

        // limpa a busca para já digitar o próximo código
        txtBusca.setText("");
        txtBusca.requestFocusInWindow();
    }

    private void removerMarcados() {
        List<String> marcados = listaSelecionados.getSelectedValuesList();
        if (marcados.isEmpty()) {
            return;
        }
        marcados.forEach(selecionados::remove);
        atualizarListaSelecionados();
    }

    private void atualizarListaSelecionados() {
        modeloSelecionados.clear();
        modeloSelecionados.addAll(selecionados);
        atualizarContadores();
    }

    private void atualizarContadores() {
        if (pastaAtual == null) {
            lblResultado.setText("Escolha uma pasta em \"Trocar pasta...\"");
        } else {
            lblResultado.setText(modeloResultado.size() + " de " + todosArquivos.size() + " arquivo(s)");
        }
        lblSelecionados.setText(selecionados.size() + " selecionado(s)");
        btnOk.setText(selecionados.isEmpty() ? "Abrir" : "Abrir " + selecionados.size() + " arquivo(s)");
        btnOk.setEnabled(!selecionados.isEmpty());
    }

    private void confirmar() {
        List<Path> caminhos = new ArrayList<>();
        for (String nome : selecionados) {
            caminhos.add(pastaAtual.resolve(nome));
        }
        resultadoFinal = caminhos;
        dispose();
    }
}
