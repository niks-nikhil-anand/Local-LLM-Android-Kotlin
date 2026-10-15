# Test fixtures

- `models_list*.json`, `model_details*.json`, `repo_tree.json`: trimmed real responses from the
  public Hugging Face Hub API (captured 2026-10-14).
- `stories260K.header.gguf`: the first 14,152 bytes (exactly the GGUF header: metadata and tensor
  directory, no tensor data) of `tinyllamas/stories260K.gguf` from the `ggml-org/models`
  repository, a tiny llama2.c-trained test model. The full file is 1,185,376 bytes.
