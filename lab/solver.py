"""Task 5g: probable-surface picture from a cane-like sweep (THEORY, offline only).

Each ping at angle theta gives an echo envelope over distance. Every distance bin is one equation:
'the echo here = the sum of what sits in the cells at this distance inside the beam'. All pings
together are solved with smoothed (regularized) least squares; plain elimination would blow up on noise.

Four states per cell: ECHO (clearly something), PROBABLE (maybe, blurred or guessed; never green),
UNKNOWN (default), CLEAR (only where sound passed through on its way to a confirmed farther echo,
seen by at least 2 pings). Silence alone never earns CLEAR.
"""
import numpy as np

from . import C
from .echo import CLEAR, ECHO, PROBABLE, UNKNOWN
from . import png


class Grid:
    def __init__(self, width_m=4.0, depth_m=4.0, cell_m=0.1):
        self.cell = cell_m
        self.xs = np.arange(-width_m / 2 + cell_m / 2, width_m / 2, cell_m)
        self.ys = np.arange(cell_m / 2, depth_m, cell_m)
        X, Y = np.meshgrid(self.xs, self.ys)
        self.x, self.y = X.ravel(), Y.ravel()
        self.r = np.hypot(self.x, self.y)
        self.ang = np.arctan2(self.x, self.y)
        self.shape = (len(self.ys), len(self.xs))


def beam(dtheta, beam_deg):
    return np.exp(-0.5 * (np.degrees(dtheta) / (beam_deg / 2.355)) ** 2)


def forward_one(grid, theta, rate, n_bins, beam_deg=40.0, bandwidth=2000.0, step=1):
    """Rows for one ping: distance bins (every `step`-th) x cells."""
    width = rate / bandwidth / 2.355
    k = np.arange(0, n_bins, step)
    kc = 2 * grid.r / C * rate
    w = beam(grid.ang - theta, beam_deg) / np.maximum(grid.r, 0.1) ** 2
    return np.exp(-0.5 * ((k[:, None] - kc[None, :]) / width) ** 2) * w[None, :]


def forward(grid, angles, rate, n_bins, beam_deg=40.0, bandwidth=2000.0):
    """Matrix A: rows = (ping, distance bin), columns = cells. Only for small tests: it grows fast."""
    return np.vstack([forward_one(grid, th, rate, n_bins, beam_deg, bandwidth) for th in angles])


def laplacian(shape):
    ny, nx = shape
    n = ny * nx
    L = np.zeros((n, n))
    idx = np.arange(n).reshape(ny, nx)
    for a, b in ((idx[:, :-1], idx[:, 1:]), (idx[:-1, :], idx[1:, :])):
        a, b = a.ravel(), b.ravel()
        np.add.at(L, (a, a), 1)
        np.add.at(L, (b, b), 1)
        L[a, b] -= 1
        L[b, a] -= 1
    return L


def solve(profiles, angles, rate, grid, beam_deg=40.0, bandwidth=2000.0, smooth=1e-3, ridge=1e-4):
    """Smoothed least squares: minimize |A x - e|^2 + smooth |L x|^2 + ridge |x|^2, then x >= 0.
    Returns x reshaped to the grid, scaled so the strongest cell = 1."""
    # distance bins much finer than a cell add work but no detail: keep every n-th one, but keep the
    # echo's blur at least one step wide or the model and the data stop matching
    decim = max(1, min(int(grid.cell / (C / 2 / rate) / 3), int(rate / bandwidth / 2.355)))
    n_bins = profiles.shape[1]
    profiles = profiles[:, ::decim]       # keep every decim-th bin; the model samples the same bins
    # add the pings up one at a time (the full matrix can be gigabytes on a 100-ping sweep)
    AtA = np.zeros((grid.r.size, grid.r.size))
    Ate = np.zeros(grid.r.size)
    for th, e in zip(angles, profiles):
        A = forward_one(grid, th, rate, n_bins, beam_deg, bandwidth, decim)
        AtA += A.T @ A
        Ate += A.T @ e
    L = laplacian(grid.shape)
    tr = np.trace(AtA) / AtA.shape[0]
    M = AtA + smooth * tr * (L.T @ L) + ridge * tr * np.eye(AtA.shape[0])
    x = np.linalg.solve(M, Ate)
    x = np.clip(x, 0, None)
    if x.max() > 0:
        x = x / x.max()
    return x.reshape(grid.shape)


def classify(img, profiles, angles, rate, grid, noise, beam_deg=40.0, hi=0.5, lo=0.2, z=6.0,
             blank_m=0.15, min_votes=2):
    """Four-state map (see module doc)."""
    st = np.full(img.size, UNKNOWN, dtype=object)
    v = img.ravel()
    st[v >= lo] = PROBABLE
    st[v >= hi] = ECHO
    votes = np.zeros(img.size, dtype=int)
    blank = int(np.ceil(2 * blank_m / C * rate))
    half = np.radians(beam_deg) / 4
    for i, th in enumerate(angles):
        p = profiles[i].copy()
        p[:blank] = 0
        hit = np.nonzero(p > z * noise)[0]
        if len(hit) == 0:
            continue
        r_echo = hit[0] / rate * C / 2
        inside = (np.abs(grid.ang - th) <= half) & (grid.r < r_echo - 2 * grid.cell) & (grid.r > blank_m)
        votes[inside] += 1
    clear = (votes >= min_votes) & (v < lo)
    st[clear] = CLEAR
    return st.reshape(grid.shape), votes.reshape(grid.shape)


COLORS = {ECHO: (220, 40, 40), PROBABLE: (240, 160, 40), UNKNOWN: (70, 70, 70), CLEAR: (40, 190, 80)}


def picture(states, path, k=8):
    """Phone at the bottom middle, looking up the picture."""
    h, w = states.shape
    rgb = np.zeros((h, w, 3), dtype=np.uint8)
    for s, c in COLORS.items():
        rgb[states == s] = c
    rgb = rgb[::-1]
    png.write(path, png.scale(rgb, k))
