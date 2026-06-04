package com.xiaoyu.worldlogger.ai;

/**
 * 一次 AI 对话完成后的结果。
 *
 * @param text AI 最终回复文本。
 * @param responseId OpenAI response id，用于下一轮对话延续上下文。
 */
public record AiRunResult(String text, String responseId) {}
