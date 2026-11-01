package dev.pocketengine.features.skills;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded text-only ZIP transport. Nothing is extracted or executed. */
final class SkillArchive {
    static final int MAX_ARCHIVE_BYTES = 1_100_000;
    private static final int MAX_ENTRIES = 128;
    private SkillArchive() {}

    static JSONObject read(InputStream input) throws Exception {
        if (input == null) throw new IllegalArgumentException("请选择技能ZIP文件");
        byte[] archive = boundedRead(input, MAX_ARCHIVE_BYTES);
        Map<String,ZipEntry> directory = validateDirectory(archive);
        JSONObject files = new JSONObject();
        Set<String> entries = new HashSet<>();
        String root = null;
        long totalBytes = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                String checked = entry.isDirectory() ? name.substring(0, name.length() - 1) : name;
                // The extra length allows the required enclosing skill-name directory.
                if (checked.length() > 225 || !entries.add(checked) || entries.size() > MAX_ENTRIES)
                    throw new IllegalArgumentException("ZIP包含重复路径或过多条目");
                int slash = checked.indexOf('/');
                String parent = slash < 0 ? checked : checked.substring(0, slash);
                if (!parent.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || parent.length() > 64)
                    throw new IllegalArgumentException("ZIP根目录必须是技能名称");
                if (root == null) root = parent;
                if (!root.equals(parent)) throw new IllegalArgumentException("一次只能导入一个技能目录");
                String relative = slash < 0 ? "" : SkillBook.path(checked.substring(slash + 1));
                if (entry.isDirectory()) {
                    if (zip.read() != -1) throw new IllegalArgumentException("ZIP目录不能包含文件内容");
                    verifyEntry(entry,directory.get(name));
                    continue;
                }
                if (relative.isEmpty()) throw new IllegalArgumentException("请将包含SKILL.md的技能目录整体压缩");
                if (files.length() >= 41) throw new IllegalArgumentException("一次最多导入40份文本资源");
                byte[] bytes = boundedRead(zip, 256_000);
                verifyEntry(entry,directory.get(name));
                totalBytes += bytes.length;
                if (totalBytes > 1_000_000) throw new IllegalArgumentException("单个技能文本包不得超过1MB");
                String text = utf8(bytes,0,bytes.length);
                if (text.length() > 64_000) throw new IllegalArgumentException("资源必须是64000字以内的文本");
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c < 32 && c != '\n' && c != '\r' && c != '\t')
                        throw new IllegalArgumentException("技能包只支持UTF-8文本，不支持二进制附件");
                }
                files.put(relative, text);
            }
        }
        if (entries.size() != directory.size()) throw new IllegalArgumentException("ZIP目录与内容不一致");
        if (!files.has("SKILL.md")) throw new IllegalArgumentException("ZIP技能目录缺少SKILL.md");
        JSONObject metadata = SkillManifest.parse(files.getString("SKILL.md"));
        if (!metadata.getString("name").equals(root)) throw new IllegalArgumentException("ZIP目录名称与SKILL.md名称不一致");
        // A file cannot also be an ancestor directory of another file.
        java.util.Iterator<String> names = files.keys();
        while (names.hasNext()) {
            String name = names.next();
            for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1)) {
                if (files.has(name.substring(0, slash))) throw new IllegalArgumentException("ZIP文件与目录路径冲突");
            }
        }
        return files;
    }

    static byte[] write(String name, JSONObject files) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            JSONArray paths = SkillBook.resourceNames(files);
            for (int i = 0; i < paths.length(); i++) {
                String path = SkillBook.path(paths.getString(i));
                ZipEntry entry = new ZipEntry(name + "/" + path);
                entry.setTime(0); // Content identity is the package digest, not ZIP timestamps.
                zip.putNextEntry(entry);
                zip.write(files.getString(path).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        if (bytes.size() > MAX_ARCHIVE_BYTES) throw new IllegalArgumentException("技能ZIP文件过大");
        return bytes.toByteArray();
    }

    private static byte[] boundedRead(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer, 0, Math.min(buffer.length, limit - bytes.size() + 1))) != -1) {
            if (count == 0) { // Also support streams that occasionally return no bytes.
                int next = input.read();
                if (next == -1) break;
                bytes.write(next);
            } else bytes.write(buffer, 0, count);
            if (bytes.size() > limit) throw new IllegalArgumentException("技能ZIP或解压后文本超过大小限制");
        }
        return bytes.toByteArray();
    }

    /** ZipInputStream checks payload CRC but accepts a missing central directory. Reject it here. */
    private static Map<String,ZipEntry> validateDirectory(byte[] bytes) {
        int end = -1;
        for (int i = bytes.length - 22; i >= Math.max(0, bytes.length - 65_557); i--) {
            if (number(bytes, i, 4) == 0x06054b50L && i + 22 + number(bytes, i + 20, 2) == bytes.length) { end = i; break; }
        }
        if (end < 0 || number(bytes, 0, 4) != 0x04034b50L || number(bytes, end + 4, 4) != 0)
            throw new IllegalArgumentException("无效、不完整或分卷ZIP");
        int count = (int) number(bytes, end + 10, 2);
        long start = number(bytes, end + 16, 4), size = number(bytes, end + 12, 4);
        if (count < 1 || count > MAX_ENTRIES || number(bytes, end + 8, 2) != count || start + size != end)
            throw new IllegalArgumentException("ZIP目录大小或条目数无效");
        long cursor = start;
        Map<String,ZipEntry> directory=new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            if (cursor < 0 || cursor + 46 > end || number(bytes, (int) cursor, 4) != 0x02014b50L)
                throw new IllegalArgumentException("ZIP目录损坏");
            int at = (int) cursor;
            int flags = (int) number(bytes, at + 8, 2), mode = (int) (number(bytes, at + 38, 4) >>> 16) & 0xf000;
            if ((flags & 1) != 0 || (mode != 0 && mode != 0x8000 && mode != 0x4000) || number(bytes, at + 34, 2) != 0)
                throw new IllegalArgumentException("ZIP不支持加密、链接或特殊文件");
            long local = number(bytes, at + 42, 4);
            int nameLength = (int) number(bytes, at + 28, 2);
            if (local < 0 || local + 30 + nameLength > start || at + 46 + nameLength > end
                    || number(bytes, (int) local, 4) != 0x04034b50L || number(bytes, (int) local + 26, 2) != nameLength)
                throw new IllegalArgumentException("ZIP文件索引无效");
            for (int j = 0; j < nameLength; j++) {
                if (bytes[(int) local + 30 + j] != bytes[at + 46 + j]) throw new IllegalArgumentException("ZIP目录与文件路径不一致");
            }
            ZipEntry expected=new ZipEntry(utf8(bytes,at+46,nameLength));
            expected.setCrc(number(bytes,at+16,4));expected.setSize(number(bytes,at+24,4));
            expected.setCompressedSize(number(bytes,at+20,4));expected.setMethod((int)number(bytes,at+10,2));
            if(directory.put(expected.getName(),expected)!=null)throw new IllegalArgumentException("ZIP包含重复路径");
            cursor += 46 + number(bytes, at + 28, 2) + number(bytes, at + 30, 2) + number(bytes, at + 32, 2);
            if (cursor > end) throw new IllegalArgumentException("ZIP目录损坏");
        }
        if (cursor != end) throw new IllegalArgumentException("ZIP目录损坏");
        return directory;
    }

    private static void verifyEntry(ZipEntry actual,ZipEntry expected) {
        if(expected==null||actual.getCrc()!=expected.getCrc()||actual.getSize()!=expected.getSize()
                ||actual.getCompressedSize()!=expected.getCompressedSize()||actual.getMethod()!=expected.getMethod())
            throw new IllegalArgumentException("ZIP目录与文件校验信息不一致");
    }

    private static String utf8(byte[] bytes,int offset,int length) {
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,offset,length)).toString();}
        catch(CharacterCodingException invalid){throw new IllegalArgumentException("技能包必须是有效UTF-8文本",invalid);}
    }

    private static long number(byte[] bytes, int at, int length) {
        if (at < 0 || at + length > bytes.length) return -1;
        long value = 0;
        for (int i = 0; i < length; i++) value |= (long) (bytes[at + i] & 255) << (8 * i);
        return value;
    }
}
