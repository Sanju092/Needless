#ifndef CACTUS_NEEDLE_H
#define CACTUS_NEEDLE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Cactus Compute Needle 3 & Whistle Native C API (android-arm64 / ios-arm64)
 * Reference: https://github.com/cactus-compute/needle
 */

// Load a .cact model archive (needle3.cact or whistle.cact) from memory buffer
int needle_load(const unsigned char* cact_bytes, unsigned long long byte_len);

// Initialize static prefix with system prompt and tools JSON array.
// Returns non-negative tokenized static-prefix length on success, negative on error.
int needle_init(const char* system_prompt, const char* tools_json, const char* tool_index_path);

// Run grammar-constrained tool calling or audio->tool completion.
// Writes JSON result into out_buf (up to out_buf_cap bytes).
int needle_complete(const char* input_text_or_audio_path, int max_new_tokens, char* out_buf, int out_buf_cap);

// Transcribe 16kHz mono WAV audio clip using loaded whistle.cact weights.
int needle_transcribe(const char* wav_path, char* out_buf, int out_buf_cap);

// Compute 3,072-dimensional sentence embedding vector using Needle 3.
int needle_embed(const char* text, float* out_vec, int max_dims);

// Reset multi-turn KV cache back to the initialized static tool prefix.
void needle_reset(void);

// Return human-readable error message after a negative return code.
const char* needle_last_error(void);

#ifdef __cplusplus
}
#endif

#endif // CACTUS_NEEDLE_H
