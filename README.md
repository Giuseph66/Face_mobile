# Encoder facial pela webcam

Captura a câmera do notebook, detecta 478 pontos do rosto e desenha os pontos na própria imagem analisada. Exibe coordenadas do ponto selecionado, FPS e tempo de inferência. Processamento local; a primeira execução baixa apenas o modelo oficial.

## Executar

Requer Python 3.12+ e sessão gráfica para a janela de vídeo.

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python main.py
```

O OpenCV usado é `opencv-contrib-python`, também exigido pelo MediaPipe. Não instale `opencv-python` ou versões `headless` no mesmo ambiente: compartilham o módulo `cv2`.

Na janela:

- Clique em um ponto para consultar seu índice e suas coordenadas `x`, `y`, `z`.
- `N`: mostrar/ocultar todos os índices. Por padrão, ficam ocultos para reduzir custo e poluição visual.
- `S`: salvar a imagem analisada `.png` e os dados correspondentes `.npz` em `captures/`.
- `Q` ou `Esc`: encerrar e liberar a câmera.

```sh
# Menor resolução reduz o custo de captura, conversão e desenho.
.venv/bin/python main.py --width 480 --height 360

# Outra câmera ou imagem sem espelhamento.
.venv/bin/python main.py --camera 1 --no-mirror

# Conferir captura e inferência sem abrir janela.
.venv/bin/python main.py --check
```

## Dados do encoder

Cada `.npz` contém:

- `landmarks`: matriz `float32 (478, 3)`, nas coordenadas originais do MediaPipe.
- `encoding`: vetor `float32 (1434,)`, na ordem `x0, y0, z0, x1, y1, z1, ...`.
- `image_size`: largura e altura da imagem salva.
- `timestamp_ns`: instante de captura no relógio monotônico, em nanossegundos; não é data civil.
- `mirrored`: indica se a imagem analisada foi espelhada.

O encoder corrige a proporção entre largura e altura, centraliza os pontos na ponta do nariz (índice 1) e divide pela distância projetada entre os cantos externos dos olhos (33 e 263). Isso normaliza translação e escala. Rotação da cabeça e expressões continuam presentes. É uma representação geométrica, não um identificador de pessoa. `z` é profundidade relativa estimada, não distância em metros.

```python
import numpy as np

with np.load("captures/face_SEU_ARQUIVO.npz", allow_pickle=False) as data:
    landmarks = data["landmarks"]       # (478, 3)
    encoding = data["encoding"]         # (1434,)
    nose = encoding.reshape(-1, 3)[1]    # [0, 0, 0]
```

## Desempenho

Uma thread mantém somente o frame mais recente; frames antigos são descartados, sem fila crescente. O detector usa tracking em modo `VIDEO`, suavização para um rosto e saídas extras desativadas. O buffer RGB é reutilizado, a normalização usa NumPy e todos os marcadores são desenhados em lote. A inferência e a sobreposição usam o mesmo frame para evitar desalinhamento. Disco é acessado somente no download inicial ou ao pressionar `S`.

`--fps` solicita a taxa à câmera; o hardware pode negociar outro valor. O FPS exibido mede frames analisados por segundo, incluindo captura e desenho. Desempenho real depende de processador, câmera e iluminação.

Se a câmera não abrir, feche outros aplicativos que a estejam usando e confira `--camera`. Para testar captura sem interface gráfica, use `--check`.

Referência: [MediaPipe Face Landmarker para Python](https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker/python).
# Face_mobile
