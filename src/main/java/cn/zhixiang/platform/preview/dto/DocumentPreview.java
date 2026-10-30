package cn.zhixiang.platform.preview.dto;

/**
 * 即时私有预览结果，不落入公开缓存。
 *
 * @param pdfBase64 PDF字节的Base64编码，只读，最多20MiB原始字节
 */
public record DocumentPreview(String pdfBase64) {}
