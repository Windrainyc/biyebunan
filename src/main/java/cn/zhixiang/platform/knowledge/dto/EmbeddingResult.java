package cn.zhixiang.platform.knowledge.dto;

/**
 * 嵌入协议结果，内部使用，不向学生返回供应商信息。
 *
 * @param vector pgvector安全数值字面量，由适配器验证维数和有限数值
 * @param inputTokens 实际输入词元，供应商未返回时null
 */
public record EmbeddingResult(String vector, Long inputTokens) {}
