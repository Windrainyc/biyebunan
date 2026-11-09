package cn.zhixiang.platform.asset.service;

import java.util.Locale;

/** 文件分目录保存；目录仅用于组织对象，不代替服务端权限校验。 */
public final class AssetObjectKeys {
    private AssetObjectKeys() {}

    /**
     * 按媒体类型和归档扩展名选取固定目录，原文件名不进入对象路径。
     *
     * @param mimeType 文件媒体类型
     * @param filename 原文件名
     * @return images、videos、documents 或 codes 前缀
     */
    public static String prefix(String mimeType, String filename) {
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (mime.startsWith("image/")) return "images/";
        if (mime.startsWith("video/")) return "videos/";
        if (name.matches(".*\\.(zip|rar|7z|tar|gz|tgz|bz2|xz)$")) return "codes/";
        return "documents/";
    }
}
