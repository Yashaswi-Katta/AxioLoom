# InkMath HMER ONNX Model Export & Verification Tooling

This directory provides scripts to export Handwritten Mathematical Expression Recognition (HMER) models (e.g., CoMER or CAN) to ONNX Runtime Mobile format, or generate a lightweight zero-weight synthetic model for instantaneous verification of the offline inference pipeline.

## Overview

The InkMath Android app consumes models adhering to the `HmerModelConfig.EncoderDecoder` contract:

- **`hmer_vocab.txt`**: Strict line-indexed UTF-8 text file. Token ID is the 0-based line index. First token must be `<sos>`, second `<eos>`.
- **`hmer_encoder.onnx`**:
  - Inputs: `input_image` (`[1, 1, H, W]`, Float32), `image_mask` (`[1, 1, H, W]`, Float32).
  - Output: `feature_map` (`[1, 32, H/16, W/16]`, Float32).
- **`hmer_decoder.onnx`**:
  - Inputs: `feature_map` (`[1, 32, H/16, W/16]`, Float32), `tokens` (`[1, T]`, Int64), `coverage_past` (`[1, 1, H/16, W/16]`, Float32).
  - Outputs: `logits` (`[1, T, VocabSize]`, Float32), `coverage` (`[1, 1, H/16, W/16]`, Float32).

## Generating Verification Models (`--mode synthetic`)

To test the entire end-to-end pipeline (stroke capture -> rasterization -> ONNX engine -> coordinator -> transpiler -> CAS) without downloading heavy multi-hundred megabyte checkpoints:

```bash
pip install onnx numpy
python export_hmer_to_onnx.py --mode synthetic --output_dir ../../app/src/main/assets/hmer
```

This creates:
- `app/src/main/assets/hmer/hmer_vocab.txt`
- `app/src/main/assets/hmer/hmer_encoder.onnx`
- `app/src/main/assets/hmer/hmer_decoder.onnx`

When bundled into the Android app assets, `OnnxHmerEngine` detects these assets on startup and transitions from `ModelUnavailable` to `Ready`.

## Exporting CoMER / CAN Production Checkpoints

When fine-tuning or deploying a trained PyTorch model:

```bash
pip install torch onnx onnxruntime
python export_hmer_to_onnx.py \
    --mode comer \
    --checkpoint /path/to/comer_best.ckpt \
    --output_dir ../../app/src/main/assets/hmer \
    --quantize
```

The `--quantize` flag runs dynamic INT8 quantization via `onnxruntime.quantization.quantize_dynamic`, reducing model size by ~4x with minimal accuracy loss for fast on-device inference with XNNPACK on ARM64 devices.
