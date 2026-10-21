# Face Encoder · Android nativo

Aplicativo Kotlin independente da versão Python. Usa CameraX e MediaPipe, com vídeo nativo contínuo e sobreposição de 478 pontos por rosto. Inclui seleção por toque, coordenadas, FPS da análise e exportação.

Inclui o modo **Mãos**, com até duas mãos, 21 pontos por mão, IDs espaciais e indicação de pinça entre polegar e indicador. O seletor **Mapeamento** alterna esse modo e **Rostos** e guarda a escolha. Na primeira abertura após esta atualização, começa em Mãos.

Inclui um protótipo de identificação facial offline: cadastro de pessoas, lista/exclusão e nome sobre cada rosto reconhecido. Usa MobileFaceNet para embeddings de identidade de 128 valores, separado do encoder geométrico de 1.434 valores.

## Compilar e instalar

Requisitos: JDK 21, Android SDK 36, celular Android 8.0/API 26 ou superior. O modelo oficial já está em `app/src/main/assets/face_landmarker.task`; o APK funciona offline e não solicita acesso à internet.

Abra esta pasta no Android Studio ou execute nela:

```sh
# Neste computador, selecione o JDK 21, pois o Java padrão é 25.
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n br.com.sonolencia.faceencoder/.MainActivity
```

Em outros computadores, configure `JAVA_HOME` para seu JDK 21 e `ANDROID_HOME` para o Android SDK, ou crie `local.properties` com `sdk.dir=/caminho/do/sdk`.

## Usar

### Mãos

- Selecione **Mapeamento → Mãos**. Mostre uma ou duas mãos, que são acompanhadas sem associação a uma pessoa cadastrada.
- As mãos recebem cores, linhas entre articulações e IDs temporários, preservados enquanto o acompanhamento espacial for contínuo. Reordenação das detecções não muda o ID. Perdas, movimentos fora do alcance do tracker e cruzamentos ambíguos podem criar IDs novos. Esses números não identificam pessoas.
- Toque em um dos 21 pontos de uma mão para consultar XYZ. **Números** mostra os índices das mãos. **Pinça** é uma estimativa geométrica com histerese: distância entre polegar/indicador dividida pela largura da palma; não é uma classificação geral de gestos.
- As mãos usam cada frame analisado, com rastreamento do MediaPipe em VIDEO e FPS mostrado na imagem. A câmera nativa continua independente, os buffers são reutilizados e não há fila de frames. O limite térmico reduz a frequência em caso de aquecimento. O modo não executa o detector nem o modelo de identidade facial.
- **Salvar** exporta `hands_*.zip` em Downloads/FaceEncoder com a imagem anotada e `landmarks.json`. O JSON inclui `mode: hands`, `hands`, IDs, estado/razão de pinça, seleção e instante da análise. A imagem e os pontos pertencem ao mesmo frame.
- XY usa a imagem em pé e espelhada como o preview; Z é profundidade relativa da mão, com origem no pulso. Os pontos não são uma medição métrica de profundidade.
- Pausar ou colocar o app em segundo plano libera câmera e modelos. A análise é local e não pede internet. Este novo modo ainda aguarda build e validação no aparelho; as medições e aprovações de testes abaixo se referem à versão facial anterior.

Modelo oficial de mãos adicionado aos assets, sem novas bibliotecas:

| Modelo | Fonte | SHA-256 |
| --- | --- | --- |
| Hand Landmarker float16 v1 | [Download oficial](https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task) | `fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1` |

### Rostos

- Autorize a câmera na primeira abertura. A câmera frontal é preferida; usa a traseira se não houver frontal.
- **Máximo de rostos:** escolha 1, 2, 3 ou **4 ou mais rostos**. A última escolha fica salva, inclusive depois de fechar o aplicativo. O modo "4 ou mais" analisa até **8 rostos**; o limite aparece na tela.
- Toque em qualquer rosto para selecionar um ponto e consultar `x`, `y`, `z`. Cada rosto recebe cor e número próprios. Os números distinguem detecções do frame, não são identidades persistentes de pessoas.
- **Números:** alterna os números dos pontos; começam ocultos para manter o desenho leve.
- **Pausar/Retomar:** interrompe a captura e libera a câmera.
- **Salvar:** fica disponível após detectar um rosto e indica quando está salvando. Exporta um ZIP com `frame.png` e `landmarks.json` para `Downloads/FaceEncoder` no Android 10+. No Android 8/9, usa o diretório externo privado do aplicativo.

### Cadastrar e reconhecer

1. Toque em **Cadastrar pessoa**, informe um nome e toque em **Iniciar tutorial**.
2. Siga a demonstração animada: frente, um pouco à direita, um pouco à esquerda, queixo um pouco para cima e para baixo. São **três amostras por posição, 15 no total**, com captura automática somente depois de manter a posição por um instante. Entre algumas etapas, volte ao centro como indicado. Ficar parado de frente não completa as outras etapas.
3. Mantenha somente essa pessoa na câmera, com luz uniforme e olhos visíveis. O contorno e o progresso indicam a captura; fotos com qualidade insuficiente não contam. Se o rosto sair momentaneamente, a orientação pausa e confere a consistência dos embeddings antes de continuar. A entrada de outro rosto interrompe a captura. O tutorial pode ser cancelado e expira após dois minutos.
4. Ao concluir, o nome passa a aparecer após duas comparações positivas consecutivas. Comparações insuficientes ou ambíguas mostram **Desconhecido**. Cadastros com o mesmo nome são recusados.
5. Abra **Pessoas → Refazer guiado** para melhorar um cadastro existente. Seu ID e nome são preservados e as amostras antigas só são substituídas após concluir as cinco posições. Cancelar, falhar ou sair antes disso mantém o cadastro anterior. A tela distingue os cadastros simples antigos dos guiados. A exclusão continua disponível e pede confirmação.

Os cadastros anteriores de cinco amostras continuam legíveis. O novo leitor aceita até 15; um APK antigo limitado a cinco não consegue ler um cadastro atualizado de 15. O modelo de identidade e seu espaço de embeddings permanecem iguais. O tutorial mede posições relativas usando os landmarks, compensando espelhamento e rotação no plano; esses valores são heurísticos e não ângulos físicos calibrados. Maior variedade de poses melhora a cobertura do cadastro, sem garantir reconhecimento de perfis extremos, oclusões ou qualquer condição de iluminação. O limiar de reconhecimento não foi reduzido.

Na validação manual no A54, a sequência real concluiu 15/15, gravou a galeria protegida e retomou a sessão de reconhecimento com o limite de rostos original. Foram observadas janelas de aproximadamente 25–29 FPS da análise durante o tutorial. Essa verificação de funcionamento não mede a precisão biométrica em uma população nem substitui testes com imagens independentes de cada pose.

Durante o cadastro, a detecção verifica pelo menos dois rostos mesmo quando o seletor está em um, para evitar cadastrar com outra pessoa visível. Ao terminar, restaura o limite escolhido, sem mudar a preferência salva. Sair da tela ou pausar interrompe uma captura incompleta.

Só os embeddings e nomes do cadastro são persistidos, criptografados com AES-256-GCM e chave no Android Keystore, em arquivo atômico privado excluído de backup. O cadastro não guarda fotos. Uma falha ao ler os dados protegidos é informada e não apaga a galeria. A exportação manual **Salvar** continua sendo uma ação separada que grava imagem e geometria em Downloads.

O reconhecimento tem um único trabalho pendente, fora do executor da câmera. A cada intervalo de pelo menos 300 ms processa até dois rostos ainda sem identidade confirmada, alternando os menos recentemente analisados. Depois de duas correspondências, mantém o nome no track em memória, sem novos embeddings enquanto esse track continuar válido. A perda ou invalidação do track volta a exigir reconhecimento. Não acumula filas; cruzamentos ambíguos descartam nomes e resultados atrasados não podem atualizar outro track. O contador na imagem continua medindo a análise de pontos, não a frequência do modelo de identidade. No A54, o teste do modelo de identidade mediu aproximadamente 81 ms de mediana por invocação para dois rostos. Limite inicial: similaridade cosseno de 0,70 e margem de 0,10 em relação ao segundo candidato; estes valores são conservadores de partida, não porcentagens de confiança nem uma calibração estatística de produção.

Esta versão identifica cadastros locais e não implementa prova de vida ou autenticação de acesso. Fotografias e telas também podem produzir uma correspondência. A validação inicial usa variações sintéticas de uma imagem e outra identidade, sem estimar FAR/FRR para uma população real. Para implantar, medir com amostras independentes representativas de luz, pose, óculos e número de pessoas. Fonte, formato, hash e licenças do modelo estão em [third_party/mobilefacenet](third_party/mobilefacenet/README.md).

`frame.png` contém o mesmo frame que produziu os pontos, com todos os rostos desenhados. O JSON contém `faces`, com `landmarks` (478 linhas XYZ) e `encoding` (1.434 valores) para cada rosto, além de dimensões, `face_count`, `selected_face_index`, `selected_point`, espelhamento e instante monotônico em milissegundos. Índices no JSON começam em zero. Os campos `landmarks`/`encoding` na raiz continuam representando o primeiro rosto, para compatibilidade. O instante monotônico não é uma data civil.

O encoder mantém a convenção da versão Python: origem no nariz (1), escala pela distância projetada entre os olhos (33 e 263), correção da proporção da imagem. Expressão e rotação continuam representadas. A profundidade `z` é relativa; os dados não são um identificador de pessoa.

## Fluidez e ciclo de vida

- `STRATEGY_KEEP_ONLY_LATEST`: descarta frames antigos, sem acumular uma fila de imagens.
- Câmera nativa (`PreviewView`) e análise são independentes. Detector, conversão e liberação executam em um executor exclusivo; a interface desenha apenas os pequenos arrays de coordenadas.
- Começa pela CPU e, após detectar um rosto, compara uma única vez com a GPU. Só troca quando a GPU é pelo menos 15% mais rápida. Guarda a escolha para as próximas sessões; mudanças do sistema ou da versão do modelo invalidam a escolha. Falhas na GPU ativam CPU automaticamente.
- Usa `VIDEO` no executor exclusivo: a inferência é sequencial, sem bloquear a interface, reutilizando um buffer RGBA/MPImage. O vídeo exibe os pontos da análise mais recente; uma captura exportada sempre combina imagem e pontos do mesmo frame.
- O caminho de análise copia RGBA para um buffer próprio reutilizado e informa a rotação ao MediaPipe; não gira nem espelha bitmaps a cada frame. Converte XY para a orientação da tela e preserva a profundidade projetada pelo MediaPipe. Cria e gira o bitmap somente ao salvar, mantendo a imagem correspondente aos dados.
- Arrays XYZ e vetores do encoder são reutilizados. O desenho dos marcadores usa uma chamada `Canvas.drawPoints` por rosto.
- Solicita captura em 640×480; o CameraX pode negociar outra resolução conforme o hardware.
- Solicita 30 FPS ao preview para evitar a faixa automática de baixa frequência; o CameraX negocia conforme o suporte da câmera. Isso não garante 30 análises por segundo.
- Limita análise a 30 FPS; reduz para 24, 15 ou 10 FPS nos estados térmicos severo, crítico ou emergência, respectivamente, no Android 10+.
- O contador mede frames efetivamente analisados por segundo, em janelas de um segundo. Vários rostos aumentam o custo de inferência; a frequência da análise pode cair enquanto o vídeo continua independente. O modo de um rosto também mantém a suavização temporal nativa do MediaPipe.
- Salva somente sob solicitação e em executor separado. Ao ir para segundo plano ou pausar, libera câmera e detector. Ao voltar, recria a sessão.

FPS depende do aparelho, luz, temperatura e carga do sistema. A primeira calibração compara CPU/GPU uma vez por combinação de modelo, sistema e limite de rostos; somente a atualização dos pontos aguarda a calibração.

Na validação no Galaxy A54, o benchmark de preparo (100 amostras após aquecimento) passou de 2,14 ms com cópia/rotação de bitmap para 0,15 ms com o buffer RGBA, cerca de 93% menos tempo nessa etapa. No uso ao vivo com um rosto e o limite "4 ou mais" selecionado, a análise variou em torno de 24–29 FPS. Esses números não representam desempenho com oito rostos simultâneos.

## Validar

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
adb logcat -s FaceEncoder:I FaceEncoderTest:I AndroidRuntime:E
```

Testes locais verificam normalização, preservação da entrada, translação, escala e proporção. Os testes no celular usam uma [imagem oficial de exemplo](https://storage.googleapis.com/mediapipe-assets/portrait.jpg) e uma composição de quatro rostos derivada dela, presentes somente no APK de testes. Verificam os quatro limites, 478 pontos por rosto, atualização dos buffers reutilizados, alinhamento XYZ nas quatro rotações, espelhamento, linhas com padding e exportação de múltiplos encoders. Um benchmark compara o preparo RGBA com a rotação de bitmap no mesmo aparelho.

Testes de identidade verificam rejeição de desconhecidos e comparações ambíguas, votos temporais, expiração, mudança de ordem das detecções, cruzamentos e resultados atrasados. O controlador guiado testa as cinco posições, estabilidade, rejeição de poses erradas e resultados atrasados, além da compensação de espelhamento/rotação; repetir somente a frente não completa a sequência. No celular, também são exercitados embeddings reais, alinhamento do recorte, criptografia, persistência, exclusão, detecção de alteração do arquivo, substituição de cinco por 15 amostras preservando ID/outras pessoas, cadastro guiado com reconhecimento após recriar a sessão e cancelamento ao entrar outro rosto. O teste automatizado de poses altera profundidades dos landmarks de uma imagem fixa para exercitar o protocolo; não constitui medição de precisão com viradas reais. A segunda imagem é [business-person.png](https://storage.googleapis.com/mediapipe-assets/business-person.png), também do conjunto público do MediaPipe; os testes da galeria usam arquivos/chaves próprios e não alteram cadastros do usuário. Validação no A54: nove testes locais e oito instrumentados aprovados; build e lint sem erros.

Código principal: `MainActivity.kt` (permissão/ciclo de vida/câmera/configuração), `FaceAnalyzer.kt` (captura e inferência), `FrameImage.kt` (buffer RGBA/orientação/captura), `FaceOverlayView.kt` (pontos sobre o vídeo), `FaceEncoder.kt` (vetor normalizado) e `SnapshotStore.kt` (exportação).

Referências: [CameraX](https://developer.android.com/media/camera/camerax/analyze), [MediaPipe Android](https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker/android).
