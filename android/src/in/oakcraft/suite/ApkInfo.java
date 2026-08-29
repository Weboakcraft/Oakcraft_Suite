package in.oakcraft.suite;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads package name / versionCode / versionName straight out of an APK file's binary
 * AndroidManifest.xml, so the Suite can tell "this download is older than what is installed"
 * before handing the file to the system installer.
 */
final class ApkInfo {
    String pkg = "";
    long versionCode = 0;
    String versionName = "";

    private static final int RES_VERSION_CODE = 0x0101021b;
    private static final int RES_VERSION_NAME = 0x0101021c;

    static ApkInfo read(File apk) {
        try {
            ZipFile z = new ZipFile(apk);
            try {
                ZipEntry e = z.getEntry("AndroidManifest.xml");
                if (e == null) return null;
                InputStream in = z.getInputStream(e);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                in.close();
                return parse(bo.toByteArray());
            } finally {
                z.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    String toJson() {
        return "{\"package\":" + MainActivity.jsonStr(pkg) + ",\"versionCode\":" + versionCode + ",\"versionName\":" + MainActivity.jsonStr(versionName) + "}";
    }

    /* ----------------------------------------------------------- binary XML ----- */
    static ApkInfo parse(byte[] data) {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < 8 || (bb.getShort(0) & 0xffff) != 0x0003) return null;
        int off = bb.getShort(2) & 0xffff;
        String[] strings = null;
        int[] resIds = null;
        ApkInfo info = new ApkInfo();
        while (off + 8 <= data.length) {
            int type = bb.getShort(off) & 0xffff;
            int hsize = bb.getShort(off + 2) & 0xffff;
            int size = bb.getInt(off + 4);
            if (size < 8 || off + size > data.length) break;
            if (type == 0x0001) {
                strings = readStrings(bb, data, off);
            } else if (type == 0x0180) {
                int cnt = (size - hsize) / 4;
                resIds = new int[cnt];
                for (int i = 0; i < cnt; i++) resIds[i] = bb.getInt(off + hsize + i * 4);
            } else if (type == 0x0102 && strings != null) {
                int nameIdx = bb.getInt(off + hsize + 4);
                String el = str(strings, nameIdx);
                if ("manifest".equals(el)) {
                    int attrStart = bb.getShort(off + hsize + 8) & 0xffff;
                    int attrSize = bb.getShort(off + hsize + 10) & 0xffff;
                    int attrCount = bb.getShort(off + hsize + 12) & 0xffff;
                    if (attrSize < 20) attrSize = 20;
                    int a = off + hsize + attrStart;
                    for (int i = 0; i < attrCount && a + attrSize <= data.length; i++, a += attrSize) {
                        int an = bb.getInt(a + 4);
                        int raw = bb.getInt(a + 8);
                        int dtype = bb.get(a + 15) & 0xff;
                        int dv = bb.getInt(a + 16);
                        String name = str(strings, an);
                        int rid = (resIds != null && an >= 0 && an < resIds.length) ? resIds[an] : 0;
                        if ("package".equals(name)) {
                            info.pkg = raw >= 0 ? str(strings, raw) : (dtype == 0x03 ? str(strings, dv) : "");
                        } else if ("versionCode".equals(name) || rid == RES_VERSION_CODE) {
                            if (dtype == 0x03) { try { info.versionCode = Long.parseLong(str(strings, dv).trim()); } catch (Exception ignored) {} }
                            else info.versionCode = dv & 0xffffffffL;
                        } else if ("versionName".equals(name) || rid == RES_VERSION_NAME) {
                            info.versionName = raw >= 0 ? str(strings, raw) : (dtype == 0x03 ? str(strings, dv) : String.valueOf(dv));
                        }
                    }
                    return info;
                }
            }
            off += size;
        }
        return info.pkg.length() > 0 ? info : null;
    }

    private static String str(String[] pool, int i) {
        return (pool != null && i >= 0 && i < pool.length && pool[i] != null) ? pool[i] : "";
    }

    private static String[] readStrings(ByteBuffer bb, byte[] data, int off) {
        int hsize = bb.getShort(off + 2) & 0xffff;
        int count = bb.getInt(off + 8);
        int flags = bb.getInt(off + 16);
        int stringsStart = bb.getInt(off + 20);
        boolean utf8 = (flags & 0x100) != 0;
        if (count < 0 || count > 100000) return new String[0];
        String[] out = new String[count];
        for (int i = 0; i < count; i++) {
            try {
                int p = off + stringsStart + bb.getInt(off + hsize + i * 4);
                if (utf8) {
                    int c = data[p] & 0xff;
                    p += (c & 0x80) != 0 ? 2 : 1;                       // char count (skip)
                    int len = data[p] & 0xff;
                    if ((len & 0x80) != 0) { len = ((len & 0x7f) << 8) | (data[p + 1] & 0xff); p += 2; } else p += 1;
                    out[i] = new String(data, p, len, "UTF-8");
                } else {
                    int len = bb.getShort(p) & 0xffff;
                    if ((len & 0x8000) != 0) { len = ((len & 0x7fff) << 16) | (bb.getShort(p + 2) & 0xffff); p += 4; } else p += 2;
                    out[i] = new String(data, p, len * 2, "UTF-16LE");
                }
            } catch (Exception e) {
                out[i] = "";
            }
        }
        return out;
    }
}
