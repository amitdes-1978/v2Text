"""Random-weights whisper 'tiny' (multilingual) in whisper.cpp's ggml format.
Layout copied from whisper.cpp/models/convert-pt-to-ggml.py. Output text is gibberish;
the point is to exercise the real load + encode + decode code paths."""
import struct, sys, numpy as np
rng = np.random.default_rng(0)
V, ACTX, S, H, L, TCTX, MEL = 51865, 1500, 384, 6, 4, 448, 80
t = {}
def w(*shape): return (rng.standard_normal(shape) * 0.02).astype(np.float32)
t["encoder.conv1.weight"] = w(S, MEL, 3); t["encoder.conv1.bias"] = w(S)
t["encoder.conv2.weight"] = w(S, S, 3); t["encoder.conv2.bias"] = w(S)
t["encoder.positional_embedding"] = w(ACTX, S)
def block(p, cross=False):
    for a in (["attn"] + (["cross_attn"] if cross else [])):
        t[f"{p}.{a}.query.weight"] = w(S, S); t[f"{p}.{a}.query.bias"] = w(S)
        t[f"{p}.{a}.key.weight"] = w(S, S)
        t[f"{p}.{a}.value.weight"] = w(S, S); t[f"{p}.{a}.value.bias"] = w(S)
        t[f"{p}.{a}.out.weight"] = w(S, S); t[f"{p}.{a}.out.bias"] = w(S)
        t[f"{p}.{a}_ln.weight"] = np.ones(S, np.float32); t[f"{p}.{a}_ln.bias"] = w(S)
    t[f"{p}.mlp.0.weight"] = w(4 * S, S); t[f"{p}.mlp.0.bias"] = w(4 * S)
    t[f"{p}.mlp.2.weight"] = w(S, 4 * S); t[f"{p}.mlp.2.bias"] = w(S)
    t[f"{p}.mlp_ln.weight"] = np.ones(S, np.float32); t[f"{p}.mlp_ln.bias"] = w(S)
for i in range(L): block(f"encoder.blocks.{i}")
t["encoder.ln_post.weight"] = np.ones(S, np.float32); t["encoder.ln_post.bias"] = w(S)
t["decoder.token_embedding.weight"] = w(V, S); t["decoder.positional_embedding"] = w(TCTX, S)
for i in range(L): block(f"decoder.blocks.{i}", cross=True)
# Bias the output toward <|endoftext|> (id 50257) so greedy decoding stops quickly:
# logits = token_embedding @ ln(x); with ln weight 0 and bias = E[eot], eot has the largest score.
EOT = 50257
t["decoder.token_embedding.weight"][EOT] *= 8
t["decoder.ln.weight"] = np.zeros(S, np.float32)
t["decoder.ln.bias"] = t["decoder.token_embedding.weight"][EOT].copy()

with open(sys.argv[1], "wb") as f:
    f.write(struct.pack("i" * 12, 0x67676d6c, V, ACTX, S, H, L, TCTX, S, H, L, MEL, 1))
    filt = np.abs(rng.standard_normal((MEL, 201))).astype(np.float32) * 0.01
    f.write(struct.pack("ii", *filt.shape)); filt.tofile(f)
    tokens = [bytes([i]) for i in range(256)] + [f"tok{i}".encode() for i in range(256, 50257)]
    f.write(struct.pack("i", len(tokens)))
    for k in tokens: f.write(struct.pack("i", len(k))); f.write(k)
    for name, data in t.items():
        if name in ("encoder.conv1.bias", "encoder.conv2.bias"): data = data.reshape(data.shape[0], 1)
        n = data.ndim
        f32 = n < 2 or name in ("encoder.conv1.bias", "encoder.conv2.bias",
                                 "encoder.positional_embedding", "decoder.positional_embedding")
        data = data.astype(np.float32 if f32 else np.float16)
        nb = name.encode()
        f.write(struct.pack("iii", n, len(nb), 0 if f32 else 1))
        for i in range(n): f.write(struct.pack("i", data.shape[n - 1 - i]))
        f.write(nb); data.tofile(f)
print("tensors:", len(t))
