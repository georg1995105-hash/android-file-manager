import unittest

from check_agent_handoff import check_ci_run_result, validate

SHA = "a" * 40
REPO = "owner/repo"
CI = "https://github.com/owner/repo/actions/runs/123"


def body(phase="working", head="pending", ci="not_run", device="not_tested"):
    return (
        "<!-- agent-handoff:v1\n"
        "issue: #12\n"
        f"phase: {phase}\nhead: {head}\nci: {ci}\ndevice: {device}\n"
        "-->\n"
        "## Выполнено\n- Reviewed code\n"
        "## Подтверждено\n- No successful checks yet\n"
        "## Не проверено\n- Device still pending\n"
        "## Следующий шаг\n- Run CI\n"
        "## Риски и секреты\n- No secrets collected\n"
    )


class HandoffTest(unittest.TestCase):
    def test_legacy_not_blocked(self):
        self.assertEqual(validate("Old PR", SHA, REPO), (False, []))

    def test_working_can_be_unverified(self):
        self.assertEqual(validate(body(), SHA, REPO), (True, []))

    def test_blocked_must_be_well_formed(self):
        self.assertEqual(validate(body(phase="blocked", ci="failed " + CI), SHA, REPO), (True, []))

    def test_ready_requires_evidence(self):
        enrolled, errors = validate(body(phase="ready"), SHA, REPO)
        self.assertTrue(enrolled)
        self.assertTrue(any("current PR head" in x for x in errors))
        self.assertTrue(any("CI run link" in x for x in errors))

    def test_ready_matches_head_and_ci(self):
        data = body(phase="ready", head=SHA, ci="passed " + CI, device="not_applicable")
        self.assertEqual(validate(data, SHA, REPO), (True, []))

    def test_wrong_head_rejected(self):
        _, errors = validate(body(phase="ready", head="b" * 40, ci="passed " + CI, device="passed"), SHA, REPO)
        self.assertTrue(any("current PR head" in x for x in errors))

    def test_cross_repo_ci_link_rejected(self):
        _, errors = validate(body(ci="passed https://github.com/stranger/repo/actions/runs/2"), SHA, REPO)
        self.assertTrue(any("this repository" in x for x in errors))

    def test_duplicate_state_rejected(self):
        _, errors = validate(body().replace("phase: working", "phase: working\nphase: ready"), SHA, REPO)
        self.assertTrue(any("Duplicate field" in x for x in errors))

    def test_missing_section_rejected(self):
        _, errors = validate(body().replace("## Следующий шаг", "## Later"), SHA, REPO)
        self.assertTrue(any("Следующий шаг" in x for x in errors))

    def test_real_run_matching_head(self):
        self.assertEqual(check_ci_run_result(
            {"head_sha": SHA, "status": "completed", "conclusion": "success"}, SHA
        ), [])

    def test_real_run_rejects_failed_and_stale(self):
        errors = check_ci_run_result(
            {"head_sha": "b" * 40, "status": "completed", "conclusion": "failure"}, SHA
        )
        self.assertEqual(len(errors), 2)

    def test_real_run_rejects_pending(self):
        self.assertTrue(check_ci_run_result(
            {"head_sha": SHA, "status": "in_progress", "conclusion": None}, SHA
        ))


if __name__ == "__main__":
    unittest.main()
