# Automatizar Dobradiças

Programa em Java (Swing) que abre arquivos `.scx` e recria as furações de dobradiça de acordo com o comprimento da peça.

## Como usar

1. `compilar.bat` (ou `mvn package`) para gerar o `automatizar-dobradicas.jar`
2. `executar.bat` (ou `java -jar automatizar-dobradicas.jar`)
3. **Abrir arquivos .scx** → escolhe a pasta uma vez (fica salva), busca pelo nome e adiciona na lista da direita
   - vários códigos de uma vez: `5792 5793 5800` (ou separados por vírgula) + Enter
   - Enter na busca adiciona o que foi encontrado e limpa o campo para o próximo código
   - duplo clique adiciona/remove; Delete remove da lista de selecionados
   - também dá pra arrastar arquivos do Explorer direto para a tabela
4. A tabela mostra as medidas, quantas dobradiças existem hoje e quantas/onde vão ficar
5. **Atualizar desenho** → grava por cima do original; a cópia anterior vai para a pasta `backup` (ao lado do arquivo), com data e hora no nome

Requisito: JDK 21.

## Gerar o .exe

`gerar-exe.bat` → `build-exe\AutomatizarDobradicas\AutomatizarDobradicas.exe`

O executável leva o Java junto (~85 MB), então roda em PC sem Java. Para usar em outra máquina,
copie a pasta `build-exe\AutomatizarDobradicas` inteira (o .exe sozinho não funciona).

## Regras

LARGURA_TOTAL = atributo `Length` da peça (eixo X).

| Largura total        | Dobradiças | Posições X                                   |
|----------------------|-----------:|----------------------------------------------|
| até 900 mm           | 2          | 100 e L-100                                  |
| > 900 até 1600 mm    | 3          | pontas + meio                                |
| > 1600 até 2200 mm   | 4          | pontas + ((L-200)/3)·n + 100                 |
| > 2200 mm            | 5          | pontas + ((L-200)/4)·n + 100 (meio incluso)  |

Cada dobradiça gera 3 furos na face 5, sempre na parte de baixo do desenho:

- caneco Ø35, prof. 12 → `X`, `Y = Width - 22`
- 2 furos Ø5.1, prof. 0.25 → `X - 24` e `X + 24`, `Y = Width - 28`

Posições com casa decimal são arredondadas para 1 casa (ex.: 666.7).

## O que é considerado "furo de dobradiça" (é apagado e recriado)

`<Machining Type="2" ... Face="5">` com Ø35/prof. 12 ou Ø5.1/prof. 0.25.
Todo o resto do arquivo (fresagens, bordas, etc.) não é alterado.

## Estrutura

```
src/main/java/dobradicas
├── App.java                       -> main
├── model/ArquivoScx.java, Painel.java
├── service/CalculadoraDobradica.java  -> regras de quantidade e posição
├── service/ScxService.java            -> leitura, backup e gravação do .scx
└── ui/TelaPrincipal.java              -> tela
```
