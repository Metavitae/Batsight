"""Every task runs end to end against a pretend phone (tests/fakephone.py) and writes a report."""
import pytest

from lab import tasks
from fakephone import FakeWorld


@pytest.fixture
def world(tmp_path, monkeypatch):
    w = FakeWorld(tmp_path)
    w.install(monkeypatch)
    return w


def go(tmp_path, *a):
    tasks.main(list(a) + ["--root", str(tmp_path)])
    reports = sorted((tmp_path / "Art" / "Working").glob("*/report.md"), key=lambda p: p.stat().st_mtime_ns)
    return reports[-1].read_text()


def test_task1(world, tmp_path):
    rep = go(tmp_path, "task1", "--phone", "POCO", "--other", "8T")
    assert "heard up to 2" in rep or "heard up to 1" in rep
    assert "left vs right" in rep and "software_ms" in rep
    assert "Item 2" in rep and "NOT RUN" not in rep


def test_task2(world, tmp_path):
    rep = go(tmp_path, "task2", "--phone", "POCO")
    assert "Step B" in rep and "slow down" in rep
    assert list((tmp_path / "Art" / "Working").glob("*/*_slow8x.wav"))


def test_task3_baseline_then_hand(world, tmp_path):
    go(tmp_path, "task3", "--step", "baseline")
    world.echoes.append((0.3, 0.05))
    rep = go(tmp_path, "task3", "--step", "hand30")
    assert "changed at 30 cm" in rep or "changed at 29 cm" in rep or "changed at 31 cm" in rep


def test_task4c_pair(world, tmp_path):
    rep = go(tmp_path, "task4c")
    assert "round 5" in rep and "200." in rep or "199." in rep or "201." in rep


@pytest.mark.parametrize("t", ["task5c", "task5d", "task4a"])
def test_task5_single_phone(world, tmp_path, t):
    rep = go(tmp_path, t)
    assert "MEASURED" in rep


def test_task5e(world, tmp_path):
    assert "after 100 pings" in go(tmp_path, "task5e", "--step", "still")


def test_task5f_angles(world, tmp_path):
    for a in (0, 90, 180, 270):
        rep = go(tmp_path, "task5f", "--angle", str(a))
    assert "dead zone" in rep


def test_task5g_map(world, tmp_path):
    rep = go(tmp_path, "task5g")
    assert "_map.png" in rep
    assert list((tmp_path / "Art" / "Working").glob("*/*_map.png"))


def test_task5h(world, tmp_path):
    assert "running estimate" in go(tmp_path, "task5h")
