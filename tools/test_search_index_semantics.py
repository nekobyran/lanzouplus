#!/usr/bin/env python3
"""Regression checks for search correctness and background-index scheduling."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/cc/nkbr/lanzouplus/MainActivity.java").read_text(encoding="utf-8")
CORE = (ROOT / "app/src/main/java/cc/nkbr/lanzouplus/LanzouCore.java").read_text(encoding="utf-8")


def method_body(source: str, name: str, occurrence: int = 0) -> str:
    matches = list(re.finditer(rf"\b{re.escape(name)}\s*\([^)]*\)\s*(?:throws\s+[^{{]+)?\{{", source))
    if occurrence >= len(matches):
        raise AssertionError(f"method not found: {name}[{occurrence}]")
    start = matches[occurrence].end()
    depth = 1
    index = start
    while index < len(source) and depth:
        depth += (source[index] == "{") - (source[index] == "}")
        index += 1
    if depth:
        raise AssertionError(f"unterminated method: {name}")
    return source[start : index - 1]


class SearchIndexSemanticsTest(unittest.TestCase):
    def test_background_index_parallelism_is_independent_and_unlimited(self) -> None:
        load = method_body(MAIN, "loadSearchSettings")
        persist = method_body(MAIN, "persistSearchSettings")
        settings = method_body(MAIN, "buildSearchSettingsPanel")
        start = method_body(MAIN, "maybeStartDailyDirectoryIndex")
        workers = method_body(CORE, "directoryIndexWorkerCount")
        self.assertIn('getInt("index_threads",4)', load)
        self.assertIn('putInt("index_threads",sessionIndexConcurrency)', persist)
        self.assertIn("indexConcurrencyText(sessionIndexConcurrency)", settings)
        self.assertIn("后台索引线程数", method_body(MAIN, "indexConcurrencyText"))
        self.assertIn("最右为无限", settings)
        self.assertIn("sessionIndexConcurrency", start)
        self.assertNotIn("sessionSearchConcurrency", start)
        self.assertIn("concurrency<=0?workItems", workers)
        self.assertIn("Math.min(MAX_INDEX_HTTP_WORKERS,requested)", workers)
        self.assertNotIn("Math.min(32", method_body(CORE, "buildDailyDirectoryIndex"))

    def test_search_network_and_ui_work_are_resource_bounded(self) -> None:
        pool = method_body(CORE, "newSearchPool")
        coordinator = method_body(CORE, "SearchCoordinator")
        accept = method_body(MAIN, "acceptSearchBatch")
        drain = method_body(MAIN, "drainSearchUi")
        self.assertIn("SEARCH_HTTP_WORKERS,SEARCH_HTTP_WORKERS", pool)
        self.assertIn("new LinkedBlockingQueue<>()", pool)
        self.assertIn("262144L", pool)
        self.assertNotIn("Integer.MAX_VALUE", pool)
        self.assertNotIn("new SynchronousQueue", pool)
        self.assertIn("Math.min(SEARCH_HTTP_WORKERS", coordinator)
        self.assertIn("present!=item", accept)
        self.assertIn("SEARCH_UI_BATCH", drain)
        self.assertIn("searchUiPosted=remaining", drain)

    def test_cache_index_rebuild_is_low_priority_and_coalesced(self) -> None:
        constructor = method_body(CORE, "LanzouCore")
        maintenance = method_body(CORE, "newIndexMaintenancePool")
        changed = method_body(CORE, "directoryCacheChanged")
        daily = method_body(CORE, "buildDailyDirectoryIndex")
        self.assertIn("indexMaintenance.execute", constructor)
        self.assertNotIn("searchPool.execute", constructor)
        self.assertIn("Thread.MIN_PRIORITY", maintenance)
        self.assertIn("!dailyIndexRunning.get()", changed)
        self.assertIn("scheduleDirectorySearchIndexWarm(750L)", changed)
        self.assertIn("dailyIndexRunning.set(true)", daily)
        self.assertIn("dailyIndexRunning.set(false)", daily)

    def test_cached_search_respects_scope_recursion_pages_and_revision(self) -> None:
        cached = method_body(CORE, "cachedDirectoryMatches")
        current = method_body(CORE, "directorySearchIndexForSearch")
        self.assertIn("allowedSourceIds!=null&&!allowedSourceIds.contains(entry.sourceId)", cached)
        self.assertNotIn("!allowedSourceIds.isEmpty()", cached)
        self.assertIn("!recursiveFolders&&entry.depth>0", cached)
        self.assertIn("maxPages>0&&entry.page>maxPages", cached)
        self.assertIn("cached.revision!=revision?directorySearchIndex():cached", current)

    def test_live_parallelism_change_restarts_only_the_index_job(self) -> None:
        restart = method_body(MAIN, "restartDailyDirectoryIndex")
        start = method_body(MAIN, "maybeStartDailyDirectoryIndex")
        self.assertIn("directoryIndexGeneration.incrementAndGet()", restart)
        self.assertIn("core.cancelBackgroundIndex()", restart)
        self.assertIn("generation!=directoryIndexGeneration.get()", start)
        self.assertIn("ui.post(this::maybeStartDailyDirectoryIndex)", start)

    def test_full_source_search_and_daily_cache_preferences_are_persisted(self) -> None:
        options = method_body(MAIN, "searchOptions")
        load = method_body(MAIN, "loadSearchSettings")
        persist = method_body(MAIN, "persistSearchSettings")
        self.assertIn("sessionSearchConcurrency==0?all", options)
        self.assertIn('getInt("auto_expand_initial",3)', load)
        self.assertIn('getInt("auto_expand_follow",1)', load)
        self.assertIn('putInt("auto_expand_initial",sessionAutoExpandInitialPages)', persist)
        self.assertIn('putInt("auto_expand_follow",sessionAutoExpandFollowPages)', persist)

    def test_batch_history_is_grouped_and_can_show_its_children(self) -> None:
        bulk = method_body(MAIN, "startBulkDownloads")
        history = method_body(MAIN, "writeDownloadHistoryNow")
        render = method_body(MAIN, "renderDownloads")
        detail = method_body(MAIN, "showBatchDownloadDetails")
        self.assertIn("entry.batchId=batchId", bulk)
        self.assertIn('put("batch",entry.batchId)', history)
        self.assertIn("addBatchDownloadRow(batch)", render)
        self.assertIn("batchDownloadEntries(batchId)", detail)

    def test_folder_original_link_uses_external_browser(self) -> None:
        original = method_body(MAIN, "folderOriginalLink")
        self.assertIn("openInBrowser(url,password)", original)


if __name__ == "__main__":
    unittest.main()
