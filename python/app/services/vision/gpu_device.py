"""Explicit GPU selection; the existing Windows runtime remains CUDA by default."""
import os


def inference_device():
    device = os.getenv("LOST_SEARCH_DEVICE", "cuda")
    if device not in {"cuda", "mps"}:
        raise RuntimeError("LOST_SEARCH_DEVICE must be cuda or mps; CPU fallback is disabled")
    # The launcher must set this before the process imports torch.
    if device == "mps" and os.getenv("PYTORCH_ENABLE_MPS_FALLBACK") != "0":
        raise RuntimeError("Set PYTORCH_ENABLE_MPS_FALLBACK=0 before starting the MPS process")
    import torch
    available = torch.cuda.is_available() if device == "cuda" else torch.backends.mps.is_available()
    if not available:
        raise RuntimeError(f"Requested {device} GPU is unavailable; CPU fallback is disabled")
    return device


def is_gpu_oom(error, device):
    import torch
    if device == "cuda":
        return isinstance(error, torch.cuda.OutOfMemoryError)
    return device == "mps" and "MPS backend out of memory" in str(error)
