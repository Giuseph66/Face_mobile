# MobileFaceNet bundled model

- Export: Qualcomm AI Hub Models `mobile_facenet`, release `v0.64.0`, float TFLite.
- Archive: https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/mobile_facenet/releases/v0.64.0/mobile_facenet-tflite-float.zip
- Bundled file: `app/src/main/assets/mobilefacenet.tflite`, extracted without modification.
- SHA-256: `2046ab776fba2ec7e420767ffe7eb74a7402a8412d3d7ea8b9f4ca8420e5f3d7`.
- Model card: https://huggingface.co/qualcomm/MobileFaceNet
- Export wrapper: https://github.com/qualcomm/ai-hub-models/tree/v0.64.0/src/qai_hub_models/models/mobile_facenet
- Source weights and implementation: https://github.com/foamliu/MobileFaceNet

The upstream implementation is distributed under Apache-2.0; the AI Hub wrapper is BSD-3-Clause. The corresponding license texts are retained here and in `app/src/main/assets/licenses/` for binary distribution. Upstream reports training on refined MS-Celeb-1M. This file records provenance; it does not establish deployment-specific biometric accuracy or permissions over training data.

This exact export has two float32 inputs `[1,3,112,112]`, RGB in `[0,1]`, and output `[2,128]`. Torchvision normalization and original-plus-horizontal-flip test-time augmentation are already in the graph. Each output row is L2-normalized in Kotlin. When only one face needs work, the same crop is supplied to both inputs; when two faces need work, the invocation handles both. Do not supply an unrelated 112x112 MobileFaceNet model without updating preprocessing, the SHA check and the gallery model identifier.

Alignment follows the upstream square 112x112 five-point reference (eye centers, nose, mouth corners). Crops are sampled directly from the retained RGBA frame with bilinear interpolation; the front-camera mirror is removed before embedding. Existing 1,434-value landmark encodings remain geometric descriptors and are separate from these 128-value identity embeddings.

The gallery binds encrypted data to this model/preprocessing identifier. Replacing the model requires a deliberate re-enrollment or migration strategy.
