# Test fixtures, not documentation

The two `legacy-skill-pass47.md` and `legacy-skill-pass48.md` files here are **superseded copies of
`SKILL.md`**, kept only because `scripts/test_install.sh` needs the exact bytes those releases
shipped. `install.sh` carries their sha256 values in `LEGACY_SKILL_SHA`; the suite checks that a
sidecar-less skill with those bytes is treated as an unedited install and refreshed, and that
uninstall removes it.

Do **not** read them as guidance, and do **not** edit them. Their text predates the corrections in
the current `skills/corral/SKILL.md` (the "about 3 s" SIGKILL line, the missing heartbeat, the
missing TTL knob). A header or any other edit would change the hashes and break the legacy-skill
test, so the explanation lives in this separate file instead.

Provenance: their bytes match `skills/corral/SKILL.md` inside the pass-47 (`a6f843e`) and pass-48
(`0b85570`) release packages, verified by sha256. They are not shipped as documentation; the
installer bundle mirrors them under `tests/fixtures/` for the suite only.
