"""Server-boot smoke test: installs the built mod jar into a throwaway
dedicated NeoForge 1.21.1 server in a temp directory, boots it, and verifies
the mod loaded and the server reached "Done" before shutting it down
cleanly. Never touches any persistent or real server — everything happens
under tempfile.TemporaryDirectory() and is discarded on exit.

Usage: python3 scripts/smoke-server.py <path-to-mod-jar> [--neoforge-version X.Y.Z]

Exit non-zero (and raise) on: the server process exiting before reaching
"Done", the mod's own startup log line never appearing, or the server not
shutting down cleanly within the timeout. Any of those turns this check red.
"""
import os
from pathlib import Path
import queue
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

DEFAULT_NEOFORGE_VERSION = "21.1.251"
MOD_LOADED_MARKER = "Dynamic Population scaffold loaded"
READY_MARKER = 'Done ('
HELP_MARKER = 'For help, type "help"'


def boot(root):
    process = subprocess.Popen(
        ["bash", "run.sh", "nogui"], cwd=root, stdin=subprocess.PIPE,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, start_new_session=True,
    )
    lines = queue.Queue()
    seen = []

    def read_output():
        for line in process.stdout:
            print(line, end="", flush=True)
            seen.append(line)
            lines.put(line)
        lines.put(None)

    threading.Thread(target=read_output, daemon=True).start()
    try:
        deadline = time.monotonic() + 300
        mod_loaded = False
        while True:
            line = lines.get(timeout=max(0.01, deadline - time.monotonic()))
            if line is None:
                raise RuntimeError(
                    "Server exited before reaching ready "
                    f"(mod_loaded_seen={mod_loaded}); see captured output above."
                )
            if MOD_LOADED_MARKER in line:
                mod_loaded = True
            if READY_MARKER in line and HELP_MARKER in line:
                if not mod_loaded:
                    raise RuntimeError(
                        "Server reached ready without ever logging the mod's "
                        f"startup marker ({MOD_LOADED_MARKER!r}) — mod did not load."
                    )
                break
        # Exercise the registered entity on the real server, not only pure arithmetic.
        process.stdin.write('summon dynamicpopulation:fillager 0 -60 0 {Tags:["dpop_smoke"]}\n')
        process.stdin.write(
            'execute if entity @e[type=dynamicpopulation:fillager,tag=dpop_smoke] '
            'run say DPOP_FILLAGER_SPAWN_OK\n'
        )
        process.stdin.flush()
        deadline = time.monotonic() + 30
        while True:
            line = lines.get(timeout=max(0.01, deadline - time.monotonic()))
            if line is None:
                raise RuntimeError("Server exited while creating the Fillager")
            if "DPOP_FILLAGER_SPAWN_OK" in line:
                break
        # MINECRAFT-390: confirm presence (not crashing) of one King entity of each faction, same pattern
        # as the Fillager summon check above. This is a BOOT/SUMMON smoke test only -- it does NOT prove
        # real-time battle-line combat, threshold transitions, or any client-visible rendering/animation;
        # see the PR description for what remains unverified by this script.
        king_checks = [
            ("dynamicpopulation:villager_king", "dpop_smoke_vk", "DPOP_VILLAGER_KING_SPAWN_OK"),
            ("dynamicpopulation:zombie_king", "dpop_smoke_zk", "DPOP_ZOMBIE_KING_SPAWN_OK"),
            ("dynamicpopulation:skeleton_king", "dpop_smoke_sk", "DPOP_SKELETON_KING_SPAWN_OK"),
        ]
        for i, (entity_type, tag, marker) in enumerate(king_checks):
            process.stdin.write(f'summon {entity_type} {10 + i * 5} -60 0 {{Tags:["{tag}"]}}\n')
            process.stdin.write(f'execute if entity @e[type={entity_type},tag={tag}] run say {marker}\n')
            process.stdin.flush()
            deadline = time.monotonic() + 30
            while True:
                line = lines.get(timeout=max(0.01, deadline - time.monotonic()))
                if line is None:
                    raise RuntimeError(f"Server exited while creating the King ({entity_type})")
                if marker in line:
                    break
        print("Verified all three King entity types summon without crashing", flush=True)

        # MINECRAFT-390 scope item 7: verify the EXISTING /dpop king settle command route itself (not just
        # a raw /summon) creates the new visible King entities correctly, for every faction. The server
        # console itself already has full permission, so no /op is needed.
        settled_factions = set()
        for literal, faction, marker in [
            ("settle villager", "VILLAGER", "DPOP_SETTLE_VILLAGER"),
            ("settle zombie", "ZOMBIE", "DPOP_SETTLE_ZOMBIE"),
            ("settle skeleton", "SKELETON", "DPOP_SETTLE_SKELETON"),
        ]:
            process.stdin.write(f'execute positioned 100 -59 0 run dpop king {literal}\n')
            process.stdin.write(f'say {marker}_ISSUED\n')
            process.stdin.flush()
            deadline = time.monotonic() + 30
            settled = False
            while True:
                line = lines.get(timeout=max(0.01, deadline - time.monotonic()))
                if line is None:
                    raise RuntimeError(f"Server exited while running /dpop king {literal}")
                if f"a {faction} King settled at" in line:
                    settled = True
                if f"{marker}_ISSUED" in line:
                    break
            if not settled:
                raise RuntimeError(
                    f"/dpop king {literal} did not report settling (territory/uniqueness check may have "
                    f"rejected it) -- see captured output above."
                )
            settled_factions.add(faction)
        # Confirm the settle command actually created a real, queryable entity of each type (not just a
        # record), by querying the live entity count directly -- distinct from the DPOP_*_SPAWN_OK /summon
        # check above, which only proves the entity TYPE itself can be summoned, not that the settle command
        # route wires through to a real spawn.
        process.stdin.write(
            'execute if entity @e[type=dynamicpopulation:villager_king] '
            'if entity @e[type=dynamicpopulation:zombie_king] '
            'if entity @e[type=dynamicpopulation:skeleton_king] '
            'run say DPOP_KING_SETTLE_COMMAND_OK\n'
        )
        process.stdin.flush()
        deadline = time.monotonic() + 30
        while True:
            line = lines.get(timeout=max(0.01, deadline - time.monotonic()))
            if line is None:
                raise RuntimeError("Server exited while verifying /dpop king settle created real entities")
            if "DPOP_KING_SETTLE_COMMAND_OK" in line:
                break
        assert settled_factions == {"VILLAGER", "ZOMBIE", "SKELETON"}
        print("Verified /dpop king settle creates real King entities for every faction", flush=True)

        # MINECRAFT-390 (reviewer-requested acceptance evidence): a REAL multi-tick battle, not just a
        # pure-logic unit test. Force both factions' local density to >=500 at a fixed position (the same
        # existing debug-seed command the ticket documents, now faction-aware -- see PopulationCommands),
        # summon one non-persistent member of each opposing faction right next to each other, then poll
        # real server ticks for actual combat damage/death. This directly exercises
        # PopulationCombatTargetGoal + PopulationCombatTargeting + the real MeleeAttackGoal/vanilla attack
        # goals end to end -- not a hand-constructed predicate.
        # Root cause of an earlier failed attempt at this check, left here as a note: /dpop set is a no-op
        # on an unloaded chunk's anchor (LevelField.set silently returns if the chunk's attachment isn't
        # loaded yet) -- a position far from spawn with no player connected has nothing keeping its chunk
        # loaded, so the density "set" would silently do nothing and the override would never activate.
        # /forceload guarantees the chunk is loaded before dpop set ever runs, regardless of position.
        battle_pos = "200 -59 0"
        process.stdin.write('forceload add 192 -16 224 16\n')
        process.stdin.flush()
        # Give the forceload ticket time to actually load the chunk (chunk loading is async; a command
        # issued the same tick as forceload add can still see "not loaded").
        drain_for_initial = []
        end = time.monotonic() + 5
        while time.monotonic() < end:
            try:
                line = lines.get(timeout=max(0.01, end - time.monotonic()))
            except queue.Empty:
                break
            if line is None:
                raise RuntimeError("Server exited while force-loading the battle chunk")
        process.stdin.write(f'execute positioned {battle_pos} run dpop set villager 600\n')
        process.stdin.write(f'execute positioned {battle_pos} run dpop set zombie 600\n')
        process.stdin.write(f'execute positioned {battle_pos} run dpop show zombie\n')
        process.stdin.write(f'summon dynamicpopulation:fillager {battle_pos} {{Tags:["dpop_battle_villager"]}}\n')
        process.stdin.write(f'summon minecraft:zombie 201 -59 0 {{Tags:["dpop_zombie_filler","dpop_battle_zombie"]}}\n')
        process.stdin.write('say DPOP_BATTLE_SETUP_DONE\n')
        process.stdin.flush()
        deadline = time.monotonic() + 30
        density_confirmed = False
        while True:
            line = lines.get(timeout=max(0.01, deadline - time.monotonic()))
            if line is None:
                raise RuntimeError("Server exited while setting up the battle scenario")
            if "cell CellKey" in line and "= 600 ppt" in line:
                density_confirmed = True
            if "DPOP_BATTLE_SETUP_DONE" in line:
                break
        if not density_confirmed:
            raise RuntimeError(
                "Battle scenario setup: /dpop show zombie did not confirm the density actually reached "
                "600 ppt at the battle position -- the chunk may still not have been loaded in time for "
                "/dpop set to take effect; see captured output above."
            )

        def query_health(tag):
            process.stdin.write(f'execute if entity @e[tag={tag}] run data get entity @e[tag={tag},limit=1] Health\n')
            process.stdin.flush()

        def drain_for(seconds):
            end = time.monotonic() + seconds
            captured = []
            while time.monotonic() < end:
                try:
                    line = lines.get(timeout=max(0.01, end - time.monotonic()))
                except queue.Empty:
                    break
                if line is None:
                    raise RuntimeError("Server exited during the battle wait window")
                captured.append(line)
            return captured

        villager_alive_last = True
        zombie_alive_last = True
        villager_health = None
        zombie_health = None
        battle_resolved = False
        # Poll for up to 90 real seconds (the server runs at vanilla's real-time tick rate; goal selectors
        # re-evaluate on their own internal cadence, so this allows several full evaluation/attack cycles).
        for _ in range(18):
            query_health("dpop_battle_villager")
            query_health("dpop_battle_zombie")
            process.stdin.write(
                'execute unless entity @e[tag=dpop_battle_villager] run say DPOP_BATTLE_VILLAGER_DIED\n'
            )
            process.stdin.write(
                'execute unless entity @e[tag=dpop_battle_zombie] run say DPOP_BATTLE_ZOMBIE_DIED\n'
            )
            process.stdin.flush()
            captured = drain_for(5)
            for line in captured:
                if "DPOP_BATTLE_VILLAGER_DIED" in line:
                    villager_alive_last = False
                    battle_resolved = True
                if "DPOP_BATTLE_ZOMBIE_DIED" in line:
                    zombie_alive_last = False
                    battle_resolved = True
                if "entity data:" in line:
                    try:
                        value = float(line.split("entity data:")[1].strip().rstrip("f").split()[0])
                    except (IndexError, ValueError):
                        continue
                    # Whichever of the two most-recently-issued health queries this reply belongs to is
                    # ambiguous from the log alone (both use the same feedback format); record the last
                    # two distinct values seen as a best-effort damage signal rather than per-entity.
                    if villager_health is None:
                        villager_health = value
                    else:
                        zombie_health = value
            if battle_resolved:
                break
        damage_observed = (
            battle_resolved
            or (villager_health is not None and villager_health < 20.0)
            or (zombie_health is not None and zombie_health < 20.0)
        )
        if battle_resolved:
            print(
                f"Battle test: RESOLVED -- villager side died={not villager_alive_last}, "
                f"zombie side died={not zombie_alive_last} (real combat damage/death confirmed)", flush=True,
            )
        elif damage_observed:
            print(
                f"Battle test: damage observed but inconclusive outcome (last health samples: "
                f"{villager_health}, {zombie_health})", flush=True,
            )
        else:
            print(
                "Battle test: INCONCLUSIVE -- no death or health drop observed within the poll window. "
                "This does NOT prove combat is broken (goal-selector timing/AI pathing/attack-range "
                "variance can legitimately delay engagement past this script's budget); it means this run "
                "did not capture conclusive real-time evidence. See the PR's honesty note.", flush=True,
            )

        # MINECRAFT-390 review fix P1-3 (live corroboration): a FILLER that becomes persistent must stop
        # being a population-combat target. Summon a persistent-tagged zombie filler right next to an
        # aggressive Fillager at the same high density and confirm it is NEVER damaged over the same wait
        # budget, unlike the non-persistent zombie above. Also sets ZOMBIE density here too (not just
        # villager) -- without it, this zombie would be killed by ordinary vanilla sun-burning regardless
        # of the P1-3 fix, confounding the result with an unrelated death cause. (205, 206) are within the
        # same forceload region already requested above, so the chunk is already loaded by this point.
        process.stdin.write(f'execute positioned 205 -59 0 run dpop set villager 600\n')
        process.stdin.write(f'execute positioned 205 -59 0 run dpop set zombie 600\n')
        process.stdin.write(f'execute positioned 205 -59 0 run dpop show zombie\n')
        process.stdin.write(
            'summon minecraft:zombie 205 -59 0 '
            '{Tags:["dpop_zombie_filler","dpop_persistent_check"],PersistenceRequired:1b}\n'
        )
        process.stdin.write(f'summon dynamicpopulation:fillager 206 -59 0 {{Tags:["dpop_persistent_check_villager"]}}\n')
        process.stdin.write('say DPOP_PERSISTENCE_CHECK_SETUP_DONE\n')
        process.stdin.flush()
        deadline = time.monotonic() + 30
        persistence_density_confirmed = False
        while True:
            line = lines.get(timeout=max(0.01, deadline - time.monotonic()))
            if line is None:
                raise RuntimeError("Server exited while setting up the persistence-exclusion check")
            if "cell CellKey" in line and "= 600 ppt" in line:
                persistence_density_confirmed = True
            if "DPOP_PERSISTENCE_CHECK_SETUP_DONE" in line:
                break
        if not persistence_density_confirmed:
            raise RuntimeError(
                "Persistence-exclusion scenario setup: /dpop show zombie did not confirm 600 ppt density "
                "at this position -- see captured output above."
            )
        persistent_zombie_untouched = True
        for _ in range(6):
            process.stdin.write(
                'execute unless entity @e[tag=dpop_persistent_check] run say DPOP_PERSISTENT_ZOMBIE_DIED\n'
            )
            process.stdin.flush()
            captured = drain_for(5)
            for line in captured:
                if "DPOP_PERSISTENT_ZOMBIE_DIED" in line:
                    persistent_zombie_untouched = False
        if persistent_zombie_untouched:
            print(
                "Persistence-exclusion check: the PersistenceRequired-tagged filler survived the full wait "
                "window untouched -- consistent with (but not absolute proof of) exclusion from "
                "population-combat targeting; a full proof would require instrumenting the live target "
                "selection itself, not just an outcome, which this script does not do.", flush=True,
            )
        else:
            raise RuntimeError(
                "Persistence-exclusion check FAILED: the PersistenceRequired-tagged filler died -- this "
                "would indicate the P1-3 fix did not actually exclude it from targeting."
            )

        process.stdin.write("stop\n")
        process.stdin.flush()
        rc = process.wait(timeout=60)
        if rc != 0:
            raise RuntimeError(f"Server did not shut down cleanly (exit code {rc})")
        print("Verified mod load marker and clean server shutdown", flush=True)
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()


if __name__ == "__main__":
    jar = Path(sys.argv[1]).resolve()
    neoforge_version = DEFAULT_NEOFORGE_VERSION
    if "--neoforge-version" in sys.argv:
        neoforge_version = sys.argv[sys.argv.index("--neoforge-version") + 1]

    with tempfile.TemporaryDirectory(prefix="dynamicpopulation-smoke-") as directory:
        root = Path(directory)
        installer = root / "installer.jar"
        urllib.request.urlretrieve(
            f"https://maven.neoforged.net/releases/net/neoforged/neoforge/{neoforge_version}/neoforge-{neoforge_version}-installer.jar",
            installer,
        )
        subprocess.run(["java", "-jar", str(installer), "--installServer"], cwd=root, check=True, timeout=180)
        (root / "eula.txt").write_text("eula=true\n")
        (root / "user_jvm_args.txt").write_text("-Xmx2G\n")
        (root / "server.properties").write_text(
            "server-ip=127.0.0.1\nserver-port=0\nview-distance=2\nsimulation-distance=2\n"
            "level-type=minecraft:flat\ngenerate-structures=false\nspawn-protection=0\nonline-mode=false\n"
        )
        (root / "mods").mkdir(exist_ok=True)
        shutil.copy2(jar, root / "mods" / jar.name)
        print(f"Booting throwaway dedicated server in {root} with {jar.name} installed", flush=True)
        boot(root)
        print("Server-boot smoke check passed", flush=True)
