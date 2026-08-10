import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/cc/nkbr/lanzouplus/MainActivity.java").read_text(encoding="utf-8")
CORE = (ROOT / "app/src/main/java/cc/nkbr/lanzouplus/LanzouCore.java").read_text(encoding="utf-8")


def method_body(source: str, signature: str) -> str:
    start = source.index(signature)
    brace = source.index("{", start)
    depth = 0
    for index in range(brace, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[brace + 1:index]
    raise AssertionError(f"unterminated method: {signature}")


class StartupNetworkStabilitySemanticsTest(unittest.TestCase):
    def test_constructor_does_not_eagerly_build_directory_cache_index(self):
        constructor = method_body(CORE, "LanzouCore(Context c)")
        self.assertIn("sourceNameIndex()", constructor)
        self.assertNotIn("warmDirectorySearchIndex()", constructor)
        self.assertNotIn("directorySearchIndex()", constructor)

    def test_normal_cache_writes_only_refresh_an_existing_memory_index(self):
        changed = method_body(CORE, "private void directoryCacheChanged()")
        self.assertIn("directorySearchIndex!=null", changed)
        self.assertIn("!dailyIndexRunning.get()", changed)
        self.assertIn("scheduleDirectorySearchIndexWarm(750L)", changed)

    def test_home_prefetch_has_single_network_refresh_and_bounded_retry(self):
        prefetch = method_body(MAIN, "void prefetchHome()")
        finish = method_body(MAIN, "void finishHomePrefetch(")
        self.assertEqual(prefetch.count("browseSource(home,true)"), 1)
        self.assertIn("hasFolderCache(home,1)", prefetch)
        self.assertIn("homePrefetchInFlight", prefetch)
        self.assertIn("catch(OutOfMemoryError", prefetch)
        self.assertIn("imageCache.evictAll()", prefetch)
        self.assertIn("attempt>=4", finish)
        self.assertIn("homePrefetchRetry=null", finish)
        self.assertIn("ui.postDelayed(homePrefetchRetry,delay)", finish)

    def test_startup_update_waits_for_home_network_to_settle(self):
        update = method_body(MAIN, "void maybeCheckForUpdates()")
        self.assertIn("homePrefetchInFlight||homePrefetchRetry!=null", update)
        self.assertIn("ui.postDelayed(this::maybeCheckForUpdates,1800)", update)
        self.assertIn("STARTUP_UPDATE_CHECKED_IN_PROCESS.compareAndSet", update)

    def test_icon_download_is_bounded_and_sampled_before_decode(self):
        fetch = method_body(MAIN, "void fetchImage(String url)")
        bounded = method_body(MAIN, "static byte[] readBoundedImage(")
        decode = method_body(MAIN, "static Bitmap decodeIcon(")
        self.assertIn("MAX_ICON_BYTES", fetch)
        self.assertIn("getContentType()", fetch)
        self.assertIn("catch(OutOfMemoryError", fetch)
        self.assertIn("total>MAX_ICON_BYTES", bounded)
        self.assertIn("inJustDecodeBounds=true", decode)
        self.assertIn("inSampleSize", decode)
        self.assertRegex(MAIN, r"MAX_ICON_BYTES=2\*1024\*1024,MAX_ICON_EDGE=256")

    def test_lanzou_page_response_is_bounded_even_without_content_length(self):
        body = method_body(CORE, "static String body(HttpURLConnection c)")
        self.assertIn("MAX_PAGE_BODY_BYTES=4*1024*1024", CORE)
        self.assertIn("getContentLengthLong()", body)
        self.assertIn("declared>MAX_PAGE_BODY_BYTES", body)
        self.assertIn("total>MAX_PAGE_BODY_BYTES", body)
        self.assertIn('throw new IOException("蓝奏响应过大")', body)

    def test_low_memory_devices_use_fewer_image_workers(self):
        worker = method_body(MAIN, "static int imageWorkerCount(")
        self.assertIn("192L*1024*1024?4", worker)
        self.assertIn("512L*1024*1024?8:12", worker)
        self.assertIn("Math.min(memoryWorkers,cpuWorkers)", worker)
        self.assertIn("imageWorkerCount(Runtime.getRuntime().maxMemory()", MAIN)

    def test_activity_and_core_shutdown_reject_late_background_delivery(self):
        destroy = method_body(MAIN, "@Override protected void onDestroy()")
        close = method_body(CORE, "void close()")
        request = method_body(MAIN, "void requestImage(")
        self.assertTrue(destroy.strip().startswith("destroyed=true"))
        self.assertIn("core.close()", destroy)
        for pool in ("indexMaintenance", "searchScheduler", "searchPool", "compositeWarmPool"):
            self.assertIn(pool + ".shutdownNow()", close)
        self.assertIn("destroyed||", request)
        self.assertIn("catch(RejectedExecutionException", request)

    def test_index_work_checks_lifecycle_cancellation(self):
        build = method_body(CORE, "private DirectorySearchIndex directorySearchIndex()")
        schedule = method_body(CORE, "private void scheduleDirectorySearchIndexWarm(")
        self.assertGreaterEqual(build.count("closed.get()||Thread.currentThread().isInterrupted()"), 2)
        self.assertIn("if(closed.get())return", schedule)
        self.assertIn("catch(RejectedExecutionException", schedule)


if __name__ == "__main__":
    unittest.main()
