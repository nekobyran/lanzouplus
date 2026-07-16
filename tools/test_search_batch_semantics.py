from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
CORE = (ROOT / "app/src/main/java/cc/nkbr/lanzouplus/LanzouCore.java").read_text(
    encoding="utf-8"
)
MAIN = (ROOT / "app/src/main/java/cc/nkbr/lanzouplus/MainActivity.java").read_text(
    encoding="utf-8"
)
MODELS = (ROOT / "app/src/main/java/cc/nkbr/lanzouplus/Models.java").read_text(
    encoding="utf-8"
)
BUILD = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")


class SearchBatchSemanticsTest(unittest.TestCase):
    def test_finite_source_slice_yields_without_cancelling_or_losing_state(self):
        self.assertIn("scheduleRotationLocked", CORE)
        rotation = CORE[CORE.index("private void rotateSourceLocked") : CORE.index("private void finishSourceLocked")]
        self.assertIn("waitingGroups.addLast(key)", rotation)
        self.assertIn("activeGroups.remove(key)", rotation)
        self.assertIn("state.active=false", rotation)
        self.assertNotIn("state.terminal=true", rotation)
        self.assertNotIn("cancel(true)", rotation)

    def test_zero_budget_means_unlimited_and_is_not_clamped(self):
        self.assertRegex(MODELS, r"sourceSwitchDelayMillis==0\?0L:")
        normalized = MODELS[MODELS.index("SearchOptions normalized()") : MODELS.index("interface Progress")]
        self.assertNotRegex(normalized, r"return new SearchOptions\([^;]*sourceSwitchDelayMillis")
        self.assertRegex(CORE, r"timeoutMillis==0\?NO_DEADLINE:")
        self.assertIn('"慢源让位时间："', MAIN)
        self.assertIn('"无限"', MAIN)
        self.assertIn("seconds==0?60", MAIN)
        self.assertIn("progress>=60?0", MAIN)

    def test_page_pacing_and_network_timeout_are_independent_from_batch_budget(self):
        for name in (
            "MINIMAL_ANDROID_INITIAL_PAGE_INTERVAL_MS",
            "MINIMAL_ANDROID_NEXT_PAGE_INTERVAL_MS",
            "MINIMAL_DESKTOP_INITIAL_PAGE_INTERVAL_MS",
            "MINIMAL_DESKTOP_NEXT_PAGE_INTERVAL_MS",
            "CLASSIC_REFLOW_ANDROID_INITIAL_PAGE_INTERVAL_MS",
            "CLASSIC_REFLOW_ANDROID_NEXT_PAGE_INTERVAL_MS",
            "CLASSIC_ANDROID_INITIAL_PAGE_INTERVAL_MS",
            "CLASSIC_ANDROID_NEXT_PAGE_INTERVAL_MS",
            "CLASSIC_DESKTOP_INITIAL_PAGE_INTERVAL_MS",
            "CLASSIC_DESKTOP_NEXT_PAGE_INTERVAL_MS",
            "UNKNOWN_INITIAL_PAGE_INTERVAL_MS",
            "UNKNOWN_NEXT_PAGE_INTERVAL_MS",
        ):
            self.assertRegex(CORE, rf"\b{name}\s*=\s*\d+")
        self.assertIn("searchNetworkDeadline", CORE)
        self.assertNotIn("operationDeadline", CORE)
        self.assertRegex(CORE, r"Math\.min\(outer,next\)")
        search_source = CORE[CORE.index("Models.SourceSearch searchSource(") : CORE.index("private static JSONObject postSearchPage")]
        self.assertNotIn("sourceSwitchDelayMillis", search_source)
        self.assertIn("profile.interval(ua,1)", CORE)
        self.assertIn("awaitPageSlot(rateKey,deadline,interval,cancelled)", CORE)

    def test_pause_stops_admission_without_consuming_or_cancelling_source_state(self):
        coordinator = CORE[CORE.index("private final class SearchCoordinator") : CORE.index("DirectLink resolveDirect")]
        self.assertIn("if(cancelled||paused)return", coordinator)
        self.assertIn("progress.awaitIfPaused()", coordinator)
        stopped = coordinator[coordinator.index("private boolean stopped") : coordinator.index("private void refillActiveLocked")]
        self.assertNotIn("paused", stopped)

    def test_explicit_back_reentry_shows_history_but_navigation_can_restore_results(self):
        self.assertIn("homeSearchHistoryOnly", MAIN)
        self.assertIsNotNone(re.search(r"exitHomeSearchFocus\(\).*?homeSearchHistoryOnly=true", MAIN, re.S))
        self.assertIsNotNone(re.search(r"showHomeSearchMode\(boolean focusInput\).*?!homeSearchHistoryOnly", MAIN, re.S))
        self.assertIsNotNone(re.search(r"runSearch\(String q\).*?homeSearchHistoryOnly=false", MAIN, re.S))
        base_page = MAIN[MAIN.index("void basePage(int destination)") : MAIN.index("boolean wideNavigation()")]
        self.assertNotIn("homeSearchHistoryOnly=", base_page)

    def test_ui_copy_and_version_remain_exact(self):
        self.assertNotIn("切换下一源等待", MAIN)
        self.assertNotIn("单源延迟", MAIN)
        self.assertIn('versionName = "1.0.0"', BUILD)


if __name__ == "__main__":
    unittest.main()
