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


class LanzouBaseOriginSemanticsTest(unittest.TestCase):
    def test_requested_presets_and_custom_entry_are_available(self):
        for host in ("lanzouw.com", "lanzoup.com", "lanzouo.com", "lanzoux.com", "lanzouz.com"):
            self.assertIn(host, MAIN)
        picker = method_body(MAIN, "void showLanzouBaseOriginDialog()")
        custom = method_body(MAIN, "void showAddLanzouBaseOriginDialog()")
        self.assertIn("添加自定义基础链接", picker)
        self.assertIn("normalizePreferredSourceOrigin", custom)
        self.assertIn("persistCustomLanzouBaseOrigins", custom)

    def test_custom_origin_is_https_root_and_lanzou_only(self):
        normalize = method_body(CORE, "static String normalizePreferredSourceOrigin(")
        self.assertIn('equalsIgnoreCase("https")', normalize)
        self.assertIn("LANZOU_HOST.matcher(host).matches()", normalize)
        self.assertIn("uri.getRawUserInfo()!=null", normalize)
        self.assertIn("uri.getPort()!=-1", normalize)
        self.assertIn("uri.getRawQuery()!=null", normalize)
        self.assertIn("uri.getRawFragment()!=null", normalize)

    def test_internal_storage_stays_canonical_while_requests_are_rewritten(self):
        parse = method_body(CORE, "private static UserSourceInput parseUserSourceInput(")
        rewrite = method_body(CORE, "static String preferredLanzouUrl(")
        get_page = method_body(CORE, "private static DirectLink getPage(")
        self.assertIn("CANONICAL_SOURCE_ORIGIN+path", parse)
        self.assertIn("preferredSourceOrigin", rewrite)
        self.assertIn("source.getRawPath()", rewrite)
        self.assertIn("source.getRawQuery()", rewrite)
        self.assertIn("source.getRawFragment()", rewrite)
        self.assertIn("url=preferredLanzouUrl(url)", get_page)
        self.assertIn("String current=preferredLanzouUrl(url)", CORE)

    def test_setting_persists_and_clears_old_origin_sessions(self):
        load = method_body(MAIN, "void loadSearchSettings()")
        persist = method_body(MAIN, "void persistSearchSettings()")
        apply = method_body(MAIN, "void applyLanzouBaseOrigin(")
        self.assertIn('getString("lanzou_base_origin"', load)
        self.assertIn('putString("lanzou_base_origin",lanzouBaseOrigin)', persist)
        self.assertIn("LanzouCore.setPreferredSourceOrigin", apply)
        self.assertIn("core.clearPreferredOriginSessions()", apply)

    def test_list_copy_open_download_and_source_rows_use_preferred_origin(self):
        for signature in (
            "static String sourceInputLine(",
            "String selectionLinks(",
            "LinearLayout folderOriginalLink(",
            "void openRecognizedLink(",
            "void openInBrowser(",
            "void openLanzouList(",
            "DownloadEntry newDownloadEntry(",
        ):
            self.assertIn("preferredLanzouUrl", method_body(MAIN, signature), signature)
        self.assertIn("text(preferredLanzouUrl(x.url),9,MUTED)", MAIN)
        self.assertIn("String original=preferredLanzouUrl", MAIN)


if __name__ == "__main__":
    unittest.main()
