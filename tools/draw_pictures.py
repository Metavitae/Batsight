# Measurement pictures from recordings in the project Drive folder (run: .venv/bin/python tools/draw_pictures.py).
# 1: distance chart of the 8T at its table spot with the founder's tape marks; 2: movement picture of the first watch run.
import sys, glob, numpy as np; sys.path.insert(0,'.')
import matplotlib; matplotlib.use("Agg"); import matplotlib.pyplot as plt
from lab import wavio, echo, analysis, signals, sounds
from lab.tasks import SHORT
R=48000; ROOT="/mnt/chromeos/shared/GoogleDrive/MyDrive/One_ring/03_Projects/The Batsight/Art"
OUT=ROOT+"/Working/pictures"
# ---------- 1. distance chart
f=[x for x in sorted(glob.glob(ROOT+"/Source/*nearfar_8T_look/nearfar_look_*.wav")) if not x.endswith("_played.wav")][-1]
mono=analysis.mono(wavio.read(f)[0]); near=signals.reference(SHORT["8T"]); warp=sounds.warp_rise()
n_every=int(0.12*R); st={"short":[],"warp_rise":[]}
for i in range(100):
    nm="short" if i%2==0 else ["warp_rise","hyperspace_v"][(i//2)%2]
    if nm in st: st[nm].append(i*n_every)
cal=dict(np.load(ROOT+"/Working/nearfar_calib_8T.npz"))
fig,ax=plt.subplots(figsize=(13,6.2),dpi=110)
for nm,ref,col,lab in (("short",near,"#2a9d8f","short chirp (near view)"),("warp_rise",warp,"#e76f51","Warp sweep (far view)")):
    P=echo.profiles(mono,ref,st[nm],R,max_m=4.3)[0]; env=np.abs(P.mean(0)); r=echo.range_axis(len(env),R)
    fl=echo.echo_floor(P,R,ref,cal.get(nm))
    m=r>=0.15
    ax.plot(r[m],20*np.log10(env[m]/env[0]),color=col,lw=1.2,label=lab)
    ax.plot(r[m],20*np.log10(fl[m]/env[0]),color=col,lw=0.8,ls=":",alpha=0.7)
tape=[(0.27,"chair gear 26-28"),(0.28,"crates 27-29"),(0.39,"POCO 39"),(0.48,"ashtray/mug ~48 (photo)"),(0.72,"book 72"),
      (0.78,"book 77-78"),(0.81,"table edge 81"),(0.93,"glass door 93"),(1.78,"flight case 178"),(1.975,"ceiling 197.5"),
      (2.59,"door ~259"),(3.95,"ceiling x2 395")]
ylo,yhi=-80,-15
for i,(d,t) in enumerate(tape):
    ax.axvline(d,color="#555",lw=0.7,alpha=0.6)
    ax.text(d+0.01,yhi-2-(i%6)*3.6,t,fontsize=8,rotation=0,color="#222",va="top",bbox=dict(facecolor="white",edgecolor="none",alpha=0.7,pad=0.5))
ax.axvspan(1.05,1.35,color="#f4a261",alpha=0.12); ax.text(1.06,ylo+3,"1.1-1.3 m: not taped\n(glass-door corner?)",fontsize=8,color="#a0522d")
ax.set_xlim(0.15,4.2); ax.set_ylim(ylo,yhi)
ax.set_xlabel("distance from the 8T (m)"); ax.set_ylabel("echo strength, dB below the phone's own sound")
ax.set_title("What the 8T hears from its table spot (one recording, 50 pings each sound)\nSolid: echoes. Dotted: the bar an echo must beat. Grey lines: founder's tape measurements.",fontsize=11)
ax.legend(loc="upper right",fontsize=9); ax.grid(alpha=0.2)
fig.tight_layout(); fig.savefig(OUT+"/1_distance_chart_8T.png"); print("saved 1")
# ---------- 2. movement picture
P=np.load(sorted(glob.glob(ROOT+"/Working/*_watch_8T/watch_profiles.npy"))[-1])
r=echo.range_axis(P.shape[1],R); m=(r>=0.3)&(r<=4.0)
base=P[:20].mean(0); noise=np.sqrt((np.abs(P[:20]-base)**2).mean(0))+1e-20
# change of a short running average (4 chirps) vs the still start, in multiples of the still noise
fast=np.array([P[max(0,i-3):i+1].mean(0) for i in range(len(P))])
img=20*np.log10(np.abs(fast-base)[:,m]/(noise[m]/2)+1e-3)
t=np.arange(len(P))*0.15
fig,ax=plt.subplots(figsize=(10,8),dpi=110)
im=ax.imshow(img,aspect="auto",origin="lower",extent=[r[m][0],r[m][-1],t[0],t[-1]],cmap="magma",vmin=0,vmax=30)
fig.colorbar(im,ax=ax,label="change vs the still room (dB above the still noise)")
for y,txt in ((13.5,"door opens, founder comes in"),(20,"standing ~1.8 m"),(28,"going out"),(40,"door left ajar becomes 'normal'"),(52,"back in")):
    ax.axhline(y,color="white",lw=0.5,ls="--",alpha=0.6); ax.text(4.0,y+0.3,txt,color="white",fontsize=8,ha="right")
ax.set_xlabel("distance from the 8T (m)"); ax.set_ylabel("time (s)")
ax.set_title("Movement picture: the first watch run (8T chirping steadily for 60 s)\nBright = something changed at that distance at that moment. Event labels from the phone's timeline (UNCONFIRMED times).",fontsize=10)
fig.tight_layout(); fig.savefig(OUT+"/2_movement_picture_watch.png"); print("saved 2")
