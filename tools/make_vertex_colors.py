"""Per-vertex display colours for the CSE overlay: PCA of the (smooth) vertex embeddings -> RGB, so neighbouring body
surface gets similar colours. Same formula as DensePoseFnL/export/compare_with_detectron2.py:vertex_colors.

usage: python tools/make_vertex_colors.py DensePoseFnL/export/out/reference_480x640/vertex_embeddings.npy app/src/main/assets/vertex_colors.bin
Output: uint8 (n_vertices, 3), raw.
"""
import sys

import numpy as np

emb = np.load(sys.argv[1])
x = emb - emb.mean(0)
_, _, vt = np.linalg.svd(x, full_matrices=False)
p = x @ vt[:3].T
lo, hi = np.percentile(p, 1, axis=0), np.percentile(p, 99, axis=0)
colors = (np.clip((p - lo) / (hi - lo), 0, 1) * 255).astype(np.uint8)
colors.tofile(sys.argv[2])
print(f"{len(colors)} vertices -> {sys.argv[2]}")
