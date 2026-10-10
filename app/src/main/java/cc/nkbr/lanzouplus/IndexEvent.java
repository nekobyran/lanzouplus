package cc.nkbr.lanzouplus;

/**
 * Typed core index events.
 *
 * <p>The core used to smuggle page/progress/failure state through display
 * labels (for example {@code "源 · 文件夹 · 第 3 页"}) that the UI had to
 * re-parse with a regex. Those strings are presentation only; callers that
 * need to make decisions consume these typed events instead.</p>
 */
final class IndexEvent {
  private IndexEvent() {}

  /** Stage inside one source walk; persisted with failures so retries are scoped. */
  static final String STAGE_PROBE = "probe";
  static final String STAGE_LIST = "list";
  static final String STAGE_FOLDER = "folder";
  static final String STAGE_FLUSH = "flush";
  static final String STAGE_FINALIZE = "finalize";

  /** One directory page observed while indexing. Purely informational. */
  static final class Page {
    final String sourceId, source, folder;
    final int page, pageItems, itemsInSource;
    final boolean firstPage, lastPage;
    final long elapsedMs;

    Page(String sourceId, String source, String folder, int page, int pageItems, int itemsInSource, boolean firstPage, boolean lastPage, long elapsedMs) {
      this.sourceId = sourceId == null ? "" : sourceId;
      this.source = source == null ? "" : source;
      this.folder = folder == null ? "" : folder;
      this.page = page;
      this.pageItems = pageItems;
      this.itemsInSource = itemsInSource;
      this.firstPage = firstPage;
      this.lastPage = lastPage;
      this.elapsedMs = elapsedMs;
    }
  }

  /** Terminal outcome for one logical source. */
  static final class Source {
    final String sourceId, source;
    final boolean success, cancelled;
    final int pages, items;
    final long elapsedMs;

    Source(String sourceId, String source, boolean success, boolean cancelled, int pages, int items, long elapsedMs) {
      this.sourceId = sourceId == null ? "" : sourceId;
      this.source = source == null ? "" : source;
      this.success = success;
      this.cancelled = cancelled;
      this.pages = pages;
      this.items = items;
      this.elapsedMs = elapsedMs;
    }
  }

  /** A real, retryable-or-terminal failure. Never raised for cancellation. */
  static final class Failure {
    final String sourceId, source, folder, stage, reason, errorCode;
    final int page, retries;
    final long at;
    final boolean retryable;

    Failure(String sourceId, String source, String folder, String stage, int page, int retries, String reason, String errorCode, boolean retryable, long at) {
      this.sourceId = sourceId == null ? "" : sourceId;
      this.source = source == null ? "" : source;
      this.folder = folder == null ? "" : folder;
      this.stage = stage == null ? STAGE_FINALIZE : stage;
      this.page = page;
      this.retries = retries;
      this.reason = reason == null ? "" : reason;
      this.errorCode = errorCode == null ? "" : errorCode;
      this.retryable = retryable;
      this.at = at;
    }
  }
}
