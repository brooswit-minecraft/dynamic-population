import importlib.util
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location("smoke_server", Path(__file__).parent / "smoke-server.py")
smoke_server = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(smoke_server)

extract_combat_hits = smoke_server.extract_combat_hits
attributed_combat_hits = smoke_server.attributed_combat_hits


def _hit_line(victim_type, victim_uuid, victim_tags, attacker):
    return (
        f"[03:01:45] [Server thread/INFO] [io.gi.br.dy.DynamicPopulationMod/]: DPOP_COMBAT_HIT "
        f"victim_type={victim_type} victim_uuid={victim_uuid} victim_tags={victim_tags} "
        f"attacker=[{attacker}] damage=3.0\n"
    )


class ExtractCombatHitsTest(unittest.TestCase):
    def test_real_attacker_hit_returns_its_tags(self):
        line = _hit_line(
            "fillager", "398af968-e9ca-4bd8-8641-0679fac371f4", "dpop_persistent_check_villager,dpop_test_entity",
            "zombie uuid=542aabfd-2999-4fa8-ac8e-5ef54ddd0f9e tags=dpop_persistent_check,dpop_zombie_filler,dpop_test_entity",
        )
        hits = extract_combat_hits([line], "dpop_persistent_check_villager")
        self.assertEqual(hits, [{"dpop_persistent_check", "dpop_zombie_filler", "dpop_test_entity"}])

    def test_environmental_hit_returns_empty_attacker_tags(self):
        # The exact log line from the CI failure this ticket diagnoses (job117868226841, CI run
        # 39192522982): a sun-burn tick against the PersistenceRequired-tagged zombie, logged with no
        # entity attacker at all.
        line = _hit_line(
            "zombie", "542aabfd-2999-4fa8-ac8e-5ef54ddd0f9e",
            "dpop_persistent_check,dpop_zombie_filler,dpop_test_entity", "none(source=onFire)",
        )
        hits = extract_combat_hits([line], "dpop_persistent_check")
        self.assertEqual(hits, [set()])

    def test_hit_on_a_different_victim_is_not_returned(self):
        line = _hit_line("zombie", "x", "dpop_zombie_filler", "none(source=genericKill)")
        self.assertEqual(extract_combat_hits([line], "dpop_persistent_check"), [])


class AttributedCombatHitsTest(unittest.TestCase):
    """MINECRAFT-420 positive/negative regression coverage for the target-side persistence-exclusion
    check's actual hit-counting filter (attributed_combat_hits), run against the exact log-line shape
    the CI failure (job117868226841) produced."""

    def _onfire_line(self):
        return _hit_line(
            "zombie", "542aabfd-2999-4fa8-ac8e-5ef54ddd0f9e",
            "dpop_persistent_check,dpop_zombie_filler,dpop_test_entity", "none(source=onFire)",
        )

    def _real_hit_line(self, attacker_uuid="aaaaaaaa-0000-0000-0000-000000000000"):
        return _hit_line(
            "zombie", "542aabfd-2999-4fa8-ac8e-5ef54ddd0f9e",
            "dpop_persistent_check,dpop_zombie_filler,dpop_test_entity",
            f"zombie uuid={attacker_uuid} tags=dpop_zombie_filler,dpop_test_entity",
        )

    def test_negative_five_onfire_hits_are_not_counted(self):
        # This is the actual failure this ticket fixes: five consecutive sun-burn ticks against the
        # persistence-tagged zombie, as logged verbatim in the failing CI run. The old check (a raw
        # health-delta count) failed here with "5 hits, beyond the 4-hit grace tolerance". The fixed
        # check must see zero attributable hits.
        lines = [self._onfire_line() for _ in range(5)]
        self.assertEqual(attributed_combat_hits(lines, "dpop_persistent_check"), [])

    def test_positive_five_real_hits_are_still_counted(self):
        # If persistence-exclusion actually regressed and the zombie became a valid combat target again,
        # five genuine hits from a real attacker must still be counted -- proving the fix didn't also
        # suppress detection of an actual regression, just the environmental false positive.
        lines = [self._real_hit_line() for _ in range(5)]
        hits = attributed_combat_hits(lines, "dpop_persistent_check")
        self.assertEqual(len(hits), 5)
        self.assertTrue(all(hits))

    def test_mixed_onfire_and_real_hits_counts_only_the_real_ones(self):
        lines = [self._onfire_line(), self._real_hit_line(), self._onfire_line(), self._onfire_line()]
        hits = attributed_combat_hits(lines, "dpop_persistent_check")
        self.assertEqual(len(hits), 1)


if __name__ == "__main__":
    unittest.main()
