package cc.nkbr.lanzouplus;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.EncryptionMethod;

/**
 * 生成和读取用户密码 AES-256 加密的单文件 ZIP，并安全解压其中的图片。
 * 密码只通过 char[] 传入，不保存、不记录、不写入异常消息。
 */
final class CloudEncryptedZip {
    static final String MIME_TYPE = "application/zip";

    private static final int BUFFER_SIZE = 8192;

    private static final Set<String> IMAGE_EXTENSIONS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "jpg", "jpeg", "jfif", "png", "gif", "webp", "bmp",
                    "heic", "heif", "avif", "svg", "tif", "tiff")));

    private CloudEncryptedZip() {
    }

    /** 将单个文件以原文件名加密为 AES-256 ZIP。目标已存在时拒绝覆盖。 */
    static File encryptSingleFile(File source, File destination, char[] password)
            throws IOException {
        requirePassword(password);
        if (source == null || !source.isFile()) {
            throw new IOException("ZIP 源文件不存在");
        }
        if (destination == null) {
            throw new IOException("ZIP 目标路径为空");
        }
        if (sameFile(source, destination)) {
            throw new IOException("ZIP 目标不能覆盖源文件");
        }
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory()
                && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("无法创建 ZIP 目标目录");
        }
        if (destination.exists()) {
            throw new IOException("ZIP 目标文件已存在");
        }

        ZipParameters parameters = new ZipParameters();
        parameters.setFileNameInZip(source.getName());
        parameters.setEncryptFiles(true);
        parameters.setEncryptionMethod(EncryptionMethod.AES);
        parameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);

        try {
            new ZipFile(destination, password).addFile(source, parameters);
        } catch (ZipException error) {
            if (destination.isFile()) destination.delete();
            throw translate(error, "ZIP 加密写入失败");
        }
        return destination;
    }

    /** 读取 ZIP 中的图片条目，密码错误时抛出 {@link PasswordException}。 */
    static List<ImageEntry> listImages(File zipFile, char[] password) throws IOException {
        requireReadableZip(zipFile, password);
        try {
            ZipFile archive = new ZipFile(zipFile, password);
            List<ImageEntry> images = new ArrayList<>();
            boolean verified = false;
            for (FileHeader header : archive.getFileHeaders()) {
                if (header.isDirectory()) {
                    continue;
                }
                if (!verified) {
                    verifyPassword(archive, header);
                    verified = true;
                }
                String name = header.getFileName();
                if (isImageName(name)) {
                    images.add(new ImageEntry(name, header.getUncompressedSize()));
                }
            }
            return images;
        } catch (ZipException error) {
            throw translate(error, "ZIP 读取失败");
        }
    }

    /** 解压单个图片条目到缓存目录，保留归档内的相对路径和文件名。 */
    static File extractImageToCache(File zipFile, String entryName, char[] password, File cacheDir)
            throws IOException {
        requireReadableZip(zipFile, password);
        if (entryName == null || entryName.isEmpty()) {
            throw new IOException("ZIP 条目名为空");
        }
        if (!isImageName(entryName)) {
            throw new IOException("ZIP 条目不是图片");
        }
        File target = resolveEntryTarget(cacheDir, entryName);
        try {
            ZipFile archive = new ZipFile(zipFile, password);
            FileHeader header = archive.getFileHeader(entryName);
            if (header == null || header.isDirectory()) {
                throw new IOException("ZIP 中不存在该图片条目");
            }
            prepareCacheTarget(target);
            return writeEntry(archive, header, target);
        } catch (ZipException error) {
            throw translate(error, "ZIP 解压失败");
        }
    }

    /** 解压 ZIP 内全部图片到缓存目录，返回按归档顺序排列的文件。 */
    static List<File> extractImagesToCache(File zipFile, char[] password, File cacheDir)
            throws IOException {
        requireReadableZip(zipFile, password);
        if (cacheDir == null) {
            throw new IOException("图片缓存目录为空");
        }
        List<File> extracted = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            ZipFile archive = new ZipFile(zipFile, password);
            boolean verified = false;
            for (FileHeader header : archive.getFileHeaders()) {
                if (header.isDirectory()) {
                    continue;
                }
                if (!verified) {
                    verifyPassword(archive, header);
                    verified = true;
                }
                String name = header.getFileName();
                if (!isImageName(name)) {
                    continue;
                }
                File target = resolveEntryTarget(cacheDir, name);
                if (!seen.add(target.getPath())) {
                    throw new IOException("ZIP 中存在重复图片条目");
                }
                prepareCacheTarget(target);
                extracted.add(writeEntry(archive, header, target));
            }
        } catch (ZipException error) {
            throw translate(error, "ZIP 解压失败");
        }
        return extracted;
    }

    private static File writeEntry(ZipFile archive, FileHeader header, File target)
            throws IOException {
        try (InputStream input = archive.getInputStream(header);
             OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        } catch (IOException error) {
            target.delete();
            throw error;
        }
        return target;
    }

    private static void verifyPassword(ZipFile archive, FileHeader header) throws IOException {
        try (InputStream input = archive.getInputStream(header)) {
            input.read();
        }
    }

    private static File resolveEntryTarget(File cacheDir, String entryName) throws IOException {
        if (cacheDir == null) {
            throw new IOException("图片缓存目录为空");
        }
        File root = cacheDir.getCanonicalFile();
        String normalized = entryName.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains(":") || normalized.indexOf('\0') >= 0) {
            throw new IOException("ZIP 条目路径不安全");
        }
        File target = root;
        for (String part : normalized.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IOException("ZIP 条目路径不安全");
            }
            target = new File(target, part);
        }
        File canonical = target.getCanonicalFile();
        String prefix = root.getPath().endsWith(File.separator)
                ? root.getPath() : root.getPath() + File.separator;
        if (!canonical.getPath().startsWith(prefix)) {
            throw new IOException("ZIP 条目路径不安全");
        }
        return canonical;
    }

    private static void prepareCacheTarget(File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory()
                && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("无法创建图片缓存目录");
        }
        if (target.exists()) {
            throw new IOException("图片缓存文件已存在");
        }
    }

    private static boolean isImageName(String name) {
        if (name == null) {
            return false;
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String base = name.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        if (dot <= 0 || dot == base.length() - 1) {
            return false;
        }
        return IMAGE_EXTENSIONS.contains(base.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static boolean sameFile(File first, File second) throws IOException {
        return first.getCanonicalFile().equals(second.getCanonicalFile());
    }

    private static void requireReadableZip(File zipFile, char[] password) throws IOException {
        if (zipFile == null || !zipFile.isFile()) {
            throw new IOException("ZIP 文件不存在");
        }
        requirePassword(password);
    }

    private static void requirePassword(char[] password) throws IOException {
        if (password == null || password.length == 0) {
            throw new IOException("ZIP 密码不能为空");
        }
    }

    private static IOException translate(ZipException error, String message) {
        if (error.getType() == ZipException.Type.WRONG_PASSWORD
                || error.getMessage() != null
                && error.getMessage().toLowerCase(Locale.ROOT).contains("wrong password")) {
            return new PasswordException();
        }
        return new IOException(message, error);
    }

    static final class PasswordException extends IOException {
        PasswordException() {
            super("ZIP 密码错误");
        }
    }

    static final class ImageEntry {
        private final String name;
        private final long size;

        ImageEntry(String name, long size) {
            this.name = name;
            this.size = size;
        }

        public String name() {
            return name;
        }

        public long size() {
            return size;
        }
    }
}
