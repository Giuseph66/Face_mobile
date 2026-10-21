# Face Encoder

Visão computacional em tempo real para mapear rostos e mãos pela câmera, consultar coordenadas e exportar capturas. O repositório reúne um aplicativo Android nativo e uma versão desktop em Python, com processamento local usando MediaPipe.

| Recurso | Android · Kotlin | Desktop · Python |
| --- | --- | --- |
| Mapeamento facial | 478 pontos por rosto; até 8 rostos | 478 pontos; um rosto |
| Mapeamento de mãos | Até 2 mãos, 21 pontos por mão e estimativa de pinça | — |
| Reconhecimento facial | Cadastro guiado e identificação local com MobileFaceNet | — |
| Inspeção | Seleção por toque, coordenadas e FPS | Seleção por clique, coordenadas, FPS e tempo de inferência |
| Exportação | ZIP com imagem e JSON | PNG e NPZ |

## Estrutura

```text
android-kotlin/       Aplicativo Android, modelos e testes
  app/               Código Kotlin, recursos e assets
  third_party/       Origem e licenças do modelo de identidade
  README.md          Guia detalhado do aplicativo
model_desktop/       Aplicação Python para webcam
  main.py            Captura, inferência, visualização e exportação
  models/            Modelo facial do MediaPipe
  requirements.txt   Dependências Python
```

## Android

### Preparar e instalar

- JDK 21.
- Android SDK 36, configurado no Android Studio, em `ANDROID_HOME` ou no arquivo `android-kotlin/local.properties` (`sdk.dir=/caminho/do/sdk`).
- Aparelho com câmera e Android 8.0/API 26 ou superior.
- Para instalar via terminal: `adb` no `PATH`, depuração USB habilitada e aparelho autorizado.

Abra `android-kotlin/` no Android Studio ou execute, a partir da raiz do repositório:

```sh
cd android-kotlin
# Configure JAVA_HOME para o diretório do seu JDK 21.
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n br.com.sonolencia.faceencoder/.MainActivity
```

O APK inclui os modelos de rosto, mãos e identidade. O aplicativo funciona offline e solicita permissão de câmera; a primeira compilação pode precisar de internet para baixar Gradle e dependências.

### Usar

1. Autorize o acesso à câmera.
2. Em **Mapeamento**, escolha **Mãos** ou **Rostos**. A preferência fica salva.
3. Toque em um ponto para consultar suas coordenadas `x`, `y`, `z`; use **Números** para exibir os índices.
4. Use **Pausar/Retomar** para controlar a captura e **Salvar** para exportar o frame analisado.

**Mãos:** acompanha até duas mãos com IDs temporários, conexões entre articulações e indicação geométrica de pinça. Os IDs representam o acompanhamento espacial e podem mudar após perdas ou cruzamentos; não identificam pessoas.

**Rostos:** permite escolher 1, 2, 3 ou **4 ou mais rostos**. A última opção analisa até 8 rostos. Cada rosto recebe uma cor e um número para inspeção.

**Cadastro e reconhecimento:** em **Cadastrar pessoa**, informe um nome e siga o tutorial de cinco posições, com 15 amostras no total. O app passa a mostrar o nome dos cadastros reconhecidos; **Pessoas** permite refazer ou excluir um cadastro. Nomes e embeddings são armazenados localmente, criptografados com chave no Android Keystore; o cadastro não guarda fotos.

O reconhecimento é um protótipo, sem prova de vida ou autenticação de acesso. O modo Mãos ainda aguarda build e validação no aparelho, conforme o [guia Android](android-kotlin/README.md), que também detalha cadastro, desempenho e testes anteriores.

## Desktop

### Preparar e executar

Requer Python 3.12+, webcam e sessão gráfica para abrir a janela. Comandos para Linux/macOS, a partir da raiz do repositório:

```sh
cd model_desktop
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python main.py
```

Se o modelo não estiver em `model_desktop/models/`, a aplicação baixa a versão oficial na primeira execução. Depois disso, a análise funciona offline.

As dependências usam `opencv-contrib-python`. Evite instalar `opencv-python` ou variantes `headless` no mesmo ambiente, pois compartilham o módulo `cv2`.

### Controles

| Ação | Controle |
| --- | --- |
| Consultar índice e coordenadas de um ponto | Clique na imagem |
| Mostrar ou ocultar índices | `N` |
| Salvar imagem e dados | `S` |
| Encerrar e liberar a câmera | `Q` ou `Esc` |

### Opções de execução

Execute dentro de `model_desktop/`, com o ambiente já preparado:

```sh
# Reduzir a resolução analisada.
.venv/bin/python main.py --width 480 --height 360

# Usar outra câmera e desativar o espelhamento.
.venv/bin/python main.py --camera 1 --no-mirror

# Analisar um frame e encerrar sem abrir janela.
.venv/bin/python main.py --check
```

Também estão disponíveis `--fps` (padrão: 30) e `--model` para informar outro caminho para o modelo. A câmera padrão é `0`, com resolução solicitada de `640 × 480`.

## Exportações e coordenadas

### Android: ZIP

**Salvar** exporta `frame.png` e `landmarks.json` em um ZIP. No Android 10+, o destino é `Downloads/FaceEncoder`; no Android 8/9, é o diretório externo privado do aplicativo.

- **Rostos:** JSON com `faces`, pontos XYZ e encoder de cada rosto, dimensões, seleção, espelhamento e instante monotônico em milissegundos. Os campos `landmarks` e `encoding` na raiz representam o primeiro rosto, por compatibilidade.
- **Mãos:** JSON com `mode: hands`, lista `hands`, IDs, pontos, estado/razão de pinça e seleção. As coordenadas XY seguem a orientação e o espelhamento do preview.

A imagem exportada e os pontos pertencem ao mesmo frame. A exportação manual inclui a imagem, mesmo que o cadastro de pessoas não armazene fotos.

### Desktop: PNG e NPZ

As capturas ficam em `model_desktop/captures/`, com imagem `.png` e dados `.npz` correspondentes:

| Campo | Conteúdo |
| --- | --- |
| `landmarks` | Matriz `float32 (478, 3)` nas coordenadas originais do MediaPipe |
| `encoding` | Vetor `float32 (1434,)`: `x0, y0, z0, x1, y1, z1, ...`, normalizado |
| `image_size` | Largura e altura da imagem salva |
| `timestamp_ns` | Instante monotônico de captura em nanossegundos |
| `mirrored` | Indica se a imagem analisada foi espelhada |

Exemplo de leitura, executado dentro de `model_desktop/`:

```python
import numpy as np

with np.load("captures/face_SEU_ARQUIVO.npz", allow_pickle=False) as data:
    landmarks = data["landmarks"]       # (478, 3)
    encoding = data["encoding"]         # (1434,)
    nose = encoding.reshape(-1, 3)[1]    # [0, 0, 0]
```

### Encoder geométrico

As duas versões usam a mesma convenção facial: corrigem a proporção da imagem, centralizam os pontos na ponta do nariz (índice `1`) e normalizam a escala pela distância projetada entre os cantos externos dos olhos (`33` e `263`). Expressões e rotação da cabeça continuam representadas.

O vetor de 1.434 valores descreve a geometria facial. O reconhecimento Android usa outro modelo, MobileFaceNet, que gera embeddings de identidade de 128 valores; origem e licenças estão em [third_party/mobilefacenet](android-kotlin/third_party/mobilefacenet/README.md).

`z` representa profundidade relativa estimada, sem medida em metros. Timestamps monotônicos medem instantes na execução, sem representar data e hora civil.

## Desempenho e solução de problemas

As duas versões descartam frames antigos e reutilizam buffers para evitar filas e reduzir alocações. No Android, o preview da câmera é independente da análise; no desktop, a sobreposição usa a própria imagem analisada. O FPS exibido mede a frequência da análise, e o desempenho varia com hardware, iluminação, temperatura e quantidade de rostos.

- **Webcam não abre:** feche outros aplicativos que a estejam usando e tente outro índice com `--camera`.
- **Desktop sem sessão gráfica:** use `--check` para verificar captura e inferência sem janela; uma câmera continua necessária.
- **Análise lenta no desktop:** reduza `--width` e `--height`. `--fps` solicita uma taxa à câmera; o hardware pode negociar outro valor.
- **Android:** confira permissão de câmera e consulte o [guia do aplicativo](android-kotlin/README.md) para detalhes de ciclo de vida, limites térmicos e validação.
