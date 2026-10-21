package cc.nkbr.lanzouplus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Local market repository project: the editable unit that a publisher authors before
 * shipping a {@code sources.lzp} release.
 *
 * <p>A project keeps the repository metadata, the selected source JSON, the README and the
 * list of chosen source/documentation paths. Project save, parse and {@link #exportPlain()}
 * are plain UTF-8 JSON; images travel inside the project as Base64 so a project stays
 * self-contained with no network. Only {@link #buildRepositoryZip(AssetReader)} produces the
 * single published ZIP, and only its {@code sources.lzp} member goes through
 * {@link MarketSourceCodec}. No Android API is used, so this class runs on a plain JVM, and
 * the Base64 helpers below are local because {@code java.util.Base64} is API 26 and this app
 * supports API 24.
 */
final class MarketProject {

    /** Marker that identifies a project document, distinct from a published repository ZIP. */
    static final String PROJECT_KIND = "lzpmarket-project";

    /** Project document schema, independent from the published metadata schema 1. */
    static final int PROJECT_SCHEMA = 1;

    /** Directory that holds packaged images inside the release ZIP. */
    static final String ASSET_DIR = "assets/";

    /** Published ZIP members, in the fixed order the protocol requires. */
    static final String METADATA_ENTRY = "lzpmarket.json";
    static final String SOURCES_ENTRY = "sources.lzp";

    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\(([^)\\s]+)");
    private static final Pattern HTML_IMAGE = Pattern.compile("<img[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']");

    /**
     * Standard Base64 alphabet, used by the local encoder/decoder instead of
     * {@code java.util.Base64} so the class stays usable on API 24.
     */
    private static final char[] BASE64_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** Supplies bytes for an image the project references by HTTPS URL or local path. */
    interface AssetReader {
        byte[] read(String reference) throws Exception;
    }

    /** One packaged image: its original reference, its ZIP name and its bytes. */
    static final class Asset {
        final String reference;
        final String name;
        final byte[] bytes;

        Asset(String reference, String name, byte[] bytes) {
            this.reference = reference;
            this.name = name;
            this.bytes = bytes;
        }
    }

    final String id;
    final JSONObject metadata;
    String sourceJson;
    String readme;
    final JSONArray sourcePaths = new JSONArray();
    private final LinkedHashMap<String, Asset> assets = new LinkedHashMap<>();

    private MarketProject(String id, JSONObject metadata, String sourceJson, String readme) {
        this.id = id;
        this.metadata = metadata;
        this.sourceJson = sourceJson;
        this.readme = readme;
    }

    /**
     * Creates an empty project with a fresh identity.
     *
     * @param meta repository metadata, validated as published schema 1
     * @param sourceJson selected v2 source document (plain JSON text, may be empty)
     * @param readme README markdown text
     */
    static MarketProject create(JSONObject meta, String sourceJson, String readme) throws Exception {
        return new MarketProject(UUID.randomUUID().toString(), validated(meta),
                sourceJson == null ? "" : sourceJson, readme == null ? "" : readme);
    }

    /** Reads a project previously written by {@link #exportPlain()} or {@link #save(File)}. */
    static MarketProject parse(byte[] plain) throws Exception {
        if (plain == null) {
            throw new IOException("工程内容为空");
        }
        JSONObject value = new JSONObject(new String(plain, StandardCharsets.UTF_8));
        if (!PROJECT_KIND.equals(value.optString("kind"))) {
            throw new IOException("不是市场工程文件");
        }
        if (value.optInt("schema", 0) != PROJECT_SCHEMA) {
            throw new IOException("不支持的市场工程版本");
        }
        String rawId = value.optString("id", "").trim();
        if (rawId.isEmpty()) {
            throw new IOException("工程缺少标识");
        }
        MarketProject project = new MarketProject(rawId, validated(value.getJSONObject("metadata")),
                value.optString("sourceJson", ""), value.optString("readme", ""));
        JSONArray paths = value.optJSONArray("sourcePaths");
        if (paths != null) {
            for (int i = 0; i < paths.length(); i++) {
                String path = paths.optString(i, "").trim();
                if (!path.isEmpty()) {
                    project.sourcePaths.put(path);
                }
            }
        }
        JSONArray stored = value.optJSONArray("assets");
        if (stored != null) {
            for (int i = 0; i < stored.length(); i++) {
                JSONObject entry = stored.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                String reference = entry.optString("reference", "").trim();
                String name = entry.optString("name", "").trim();
                String base64 = entry.optString("base64", "");
                if (reference.isEmpty() || name.isEmpty() || base64.isEmpty()) {
                    throw new IOException("工程图片条目不完整");
                }
                project.assets.put(reference, new Asset(reference, name, base64Decode(base64)));
            }
        }
        return project;
    }

    /** Replaces the selected source document after the publisher edits paths in the UI. */
    void setSourceJson(String value) {
        sourceJson = value == null ? "" : value;
    }

    /** Records one selected source or folder path, keeping the insertion order without duplicates. */
    void addSourcePath(String path) {
        String value = path == null ? "" : path.trim();
        if (value.isEmpty()) {
            return;
        }
        for (int i = 0; i < sourcePaths.length(); i++) {
            if (value.equals(sourcePaths.optString(i))) {
                return;
            }
        }
        sourcePaths.put(value);
    }

    /** Number of images already carried inside the project. */
    int embeddedAssetCount() {
        return assets.size();
    }

    /**
     * Resolves every image the metadata and README reference, downloading or reading the bytes
     * through {@code reader} when the project does not already carry them, and rewrites the
     * references to {@code assets/<name>}. Packaging uses the embedded bytes, so a project always
     * ships the images it selected even when the origin is offline.
     */
    void resolveAssets(AssetReader reader) throws Exception {
        if (reader == null) {
            throw new IOException("缺少图片读取器");
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        resolved.put("icon", metadata.optString("icon", "").trim());
        resolved.put("publisherAvatar", metadata.optString("publisherAvatar", "").trim());
        for (Map.Entry<String, String> entry : resolved.entrySet()) {
            String rewritten = packageReference(entry.getValue(), reader);
            if (!rewritten.isEmpty()) {
                metadata.put(entry.getKey(), rewritten);
            }
        }
        readme = rewriteReadmeImages(readme, reader);
    }

    /** Plain UTF-8 project document. Images are Base64 so the project is self-contained. */
    byte[] exportPlain() throws Exception {
        JSONObject value = new JSONObject();
        value.put("kind", PROJECT_KIND);
        value.put("schema", PROJECT_SCHEMA);
        value.put("id", id);
        value.put("metadata", new JSONObject(metadata.toString()));
        value.put("sourceJson", sourceJson);
        value.put("readme", readme);
        value.put("sourcePaths", new JSONArray(sourcePaths.toString()));
        JSONArray stored = new JSONArray();
        for (Asset asset : assets.values()) {
            stored.put(new JSONObject()
                    .put("reference", asset.reference)
                    .put("name", asset.name)
                    .put("base64", base64Encode(asset.bytes)));
        }
        value.put("assets", stored);
        return value.toString(2).getBytes(StandardCharsets.UTF_8);
    }

    /** Writes the plain project document to disk, creating parent directories as needed. */
    void save(File file) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建工程目录");
        }
        byte[] plain = exportPlain();
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(plain);
        }
    }

    /** Reads a plain project document from disk. */
    static MarketProject load(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            return parse(readAll(in, Integer.MAX_VALUE));
        }
    }

    /**
     * Builds the single published ZIP: {@code lzpmarket.json}, {@code sources.lzp},
     * the README and optional {@code assets/}. There is no outer directory and
     * {@code sources.lzp} is always produced by {@link MarketSourceCodec}.
     *
     * @param reader supplies bytes for images not already carried by the project; may be null
     *               when every referenced image is already embedded
     */
    byte[] buildRepositoryZip(AssetReader reader) throws Exception {
        if (sourceJson.trim().isEmpty()) {
            throw new IOException("请选择要发布的源");
        }
        JSONObject sourceDocument = new JSONObject(sourceJson);
        if (sourceDocument.optInt("version", 0) != 2 || sourceDocument.optJSONArray("sources") == null) {
            throw new IOException("源文件必须是 version 2 的源文档");
        }
        if (reader != null) {
            resolveAssets(reader);
        }
        JSONObject header = new JSONObject(metadata.toString());
        // The repository link is a local project binding, not published metadata.
        header.remove("repositoryUrl");
        header.put("sources", SOURCES_ENTRY);
        // Strict published validation runs after resolution: metadata images must now be
        // assets/<name> or HTTPS, so a leftover local path fails instead of shipping.
        new MarketRepository(header);
        String readmePath = new MarketRepository(header).readme;
        String publishedReadme = readme;
        if (publishedReadme.trim().isEmpty()) {
            String title = header.optString("title").trim();
            if (title.isEmpty()) title = header.getString("name");
            publishedReadme = "# " + title + "\n\n";
            String description = header.optString("description").trim();
            if (!description.isEmpty()) publishedReadme += description + "\n\n";
            publishedReadme += "版本：" + header.getString("version") + "\n";
        }
        byte[] ciphertext = MarketSourceCodec.encode(sourceJson.getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(4096, ciphertext.length + 2048));
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.setLevel(Deflater.BEST_COMPRESSION);
            put(zip, METADATA_ENTRY, header.toString(2).getBytes(StandardCharsets.UTF_8));
            put(zip, SOURCES_ENTRY, ciphertext);
            put(zip, readmePath, publishedReadme.getBytes(StandardCharsets.UTF_8));
            for (Asset asset : assets.values()) {
                put(zip, ASSET_DIR + asset.name, asset.bytes);
            }
        }
        return buffer.toByteArray();
    }

    /** Reads the plain members of a built ZIP; used by the gate to inspect the real payload. */
    static Map<String, byte[]> readZip(byte[] zipBytes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (entries.put(entry.getName(), readAll(zip, Integer.MAX_VALUE)) != null) {
                    throw new IOException("发布包存在重复条目: " + entry.getName());
                }
            }
        }
        return entries;
    }

    private String rewriteReadmeImages(String text, AssetReader reader) throws Exception {
        String value = replaceAll(text, MARKDOWN_IMAGE, reader);
        return replaceAll(value, HTML_IMAGE, reader);
    }

    private String replaceAll(String text, Pattern pattern, AssetReader reader) throws Exception {
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            String rewritten = packageReference(matcher.group(1), reader);
            out.append(text, cursor, matcher.start(1));
            out.append(rewritten.isEmpty() ? matcher.group(1) : rewritten);
            cursor = matcher.end(1);
        }
        out.append(text, cursor, text.length());
        return out.toString();
    }

    /**
     * Returns an {@code assets/<name>} reference for one image, pulling or reusing its bytes.
     * Empty and already packaged references are returned unchanged or skipped.
     */
    private String packageReference(String raw, AssetReader reader) throws Exception {
        String reference = raw == null ? "" : raw.trim();
        if (reference.isEmpty() || reference.startsWith(ASSET_DIR)) {
            return reference;
        }
        Asset asset = assets.get(reference);
        if (asset == null) {
            byte[] bytes = reader.read(reference);
            if (bytes == null || bytes.length == 0) {
                throw new IOException("图片内容为空: " + reference);
            }
            asset = new Asset(reference, uniqueAssetName(reference, bytes), bytes);
            assets.put(reference, asset);
        }
        return ASSET_DIR + asset.name;
    }

    private String uniqueAssetName(String reference, byte[] bytes) {
        String stem = assetStem(reference);
        String extension = extensionFor(reference, bytes);
        Set<String> taken = new LinkedHashSet<>();
        for (Asset asset : assets.values()) {
            taken.add(asset.name);
        }
        String candidate = stem + extension;
        int suffix = 2;
        while (taken.contains(candidate)) {
            candidate = stem + "-" + suffix++ + extension;
        }
        return candidate;
    }

    /** Readable stem from the URL or path tail. Names avoid hashing by design. */
    private static String assetStem(String reference) {
        String tail = reference;
        int cut = Math.max(tail.lastIndexOf('/'), tail.lastIndexOf('\\'));
        if (cut >= 0) {
            tail = tail.substring(cut + 1);
        }
        int query = tail.indexOf('?');
        if (query >= 0) {
            tail = tail.substring(0, query);
        }
        int fragment = tail.indexOf('#');
        if (fragment >= 0) {
            tail = tail.substring(0, fragment);
        }
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < tail.length() && safe.length() < 48; i++) {
            char c = tail.charAt(i);
            boolean keep = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.';
            safe.append(keep ? c : '-');
        }
        String name = safe.toString().replaceAll("-{2,}", "-").replaceAll("^[-.]+", "");
        if (name.isEmpty()) {
            name = "image";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Extension from the image signature, falling back to the reference's own extension. */
    private static String extensionFor(String reference, byte[] bytes) {
        String detected = signatureExtension(bytes);
        if (detected != null) {
            return detected;
        }
        int dot = reference.lastIndexOf('.');
        if (dot > 0) {
            String tail = reference.substring(dot).toLowerCase().replaceAll("[^a-z0-9.]", "");
            if (tail.startsWith(".png") || tail.startsWith(".webp") || tail.startsWith(".gif")) {
                return tail.substring(0, 4);
            }
            if (tail.startsWith(".jpg")) {
                return ".jpg";
            }
            if (tail.startsWith(".jpeg")) {
                return ".jpeg";
            }
        }
        return ".png";
    }

    private static String signatureExtension(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return ".png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) {
            return ".jpg";
        }
        if (bytes.length >= 4 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
            return ".gif";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return ".webp";
        }
        return null;
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static JSONObject validated(JSONObject meta) throws Exception {
        if (meta == null) {
            throw new IOException("缺少仓库信息");
        }
        JSONObject copy = new JSONObject(meta.toString());
        // Repository metadata is validated as published schema 1. Local image references are
        // allowed at the project stage (an Android absolute path such as /storage/...), so the
        // image fields are blanked for the check and revalidated strictly after resolveAssets
        // has rewritten them to assets/<name> or left them as HTTPS.
        JSONObject probe = new JSONObject(copy.toString());
        if (isLocalReference(probe.optString("icon", ""))) {
            probe.put("icon", "");
        }
        if (isLocalReference(probe.optString("publisherAvatar", ""))) {
            probe.put("publisherAvatar", "");
        }
        new MarketRepository(probe);
        return copy;
    }

    /**
     * True for a reference that is not an HTTPS URL and is not an in-repository relative path.
     * Those are exactly the local file references a project may hold before packaging.
     */
    static boolean isLocalReference(String reference) {
        String value = reference == null ? "" : reference.trim();
        if (value.isEmpty() || value.startsWith("https://") || value.startsWith(ASSET_DIR)) {
            return false;
        }
        return value.startsWith("/") || value.startsWith("file:") || value.contains(":")
                || value.contains("\\");
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
            if (out.size() > limit) {
                throw new IOException("读取内容超过上限");
            }
        }
        return out.toByteArray();
    }

    /**
     * Standard Base64 encoder over {@link #BASE64_ALPHABET}. Written locally because
     * {@code java.util.Base64} only exists from API 26 while this app supports API 24.
     */
    static String base64Encode(byte[] data) {
        StringBuilder out = new StringBuilder(((data.length + 2) / 3) * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xff;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xff : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xff : 0;
            out.append(BASE64_ALPHABET[b0 >>> 2]);
            out.append(BASE64_ALPHABET[((b0 & 0x03) << 4) | (b1 >>> 4)]);
            out.append(i + 1 < data.length ? BASE64_ALPHABET[((b1 & 0x0f) << 2) | (b2 >>> 6)] : '=');
            out.append(i + 2 < data.length ? BASE64_ALPHABET[b2 & 0x3f] : '=');
        }
        return out.toString();
    }

    /** Standard Base64 decoder matching {@link #base64Encode(byte[])}, tolerating line breaks. */
    static byte[] base64Decode(String text) throws IOException {
        int[] lookup = new int[128];
        for (int i = 0; i < lookup.length; i++) {
            lookup[i] = -1;
        }
        for (int i = 0; i < BASE64_ALPHABET.length; i++) {
            lookup[BASE64_ALPHABET[i]] = i;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length() * 3 / 4);
        int buffer = 0;
        int bits = 0;
        int padding = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') {
                continue;
            }
            if (c == '=') {
                padding++;
                continue;
            }
            if (c > 127 || lookup[c] < 0) {
                throw new IOException("图片 Base64 内容无效");
            }
            buffer = (buffer << 6) | lookup[c];
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >>> bits) & 0xff);
            }
        }
        if (bits >= 6 || padding > 2 || (bits > 0 && (buffer & ((1 << bits) - 1)) != 0)) {
            throw new IOException("图片 Base64 内容无效");
        }
        return out.toByteArray();
    }
}
