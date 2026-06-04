package com.xiaoyu.worldlogger.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;

import java.net.URI;
import java.util.Locale;

/**
 * 把 AI 返回的文本转换成 Minecraft Component。
 *
 * <p>AI 不直接返回 Java 对象，所以这里约定一种安全 JSON 格式：
 * {"worldlogger_component":[{"text":"标题","color":"gold","bold":true}]}。
 * 代码只解析白名单字段，避免模型生成任意危险点击事件。</p>
 */
public final class AiComponentFormatter {
    /** AI 正文最大纯文本长度，避免一个回复生成过大的聊天组件。 */
    private static final int MAX_TEXT_LENGTH = 30000;

    /** 单个片段最大文本长度，防止一个 JSON 字段塞入超长文本。 */
    private static final int MAX_SEGMENT_TEXT_LENGTH = 4000;

    /** hover 文本最大长度，hover 太长会影响客户端阅读和网络包大小。 */
    private static final int MAX_HOVER_TEXT_LENGTH = 800;

    /** click value 最大长度，限制 URL、命令或剪贴板文本的大小。 */
    private static final int MAX_CLICK_VALUE_LENGTH = 512;

    /** 最多解析多少个片段，避免模型生成几千段导致客户端聊天组件过大。 */
    private static final int MAX_SEGMENTS = 160;

    /** JSON 嵌套最大深度，防止恶意或异常响应递归过深。 */
    private static final int MAX_DEPTH = 8;

    /** 工具类不需要实例化。 */
    private AiComponentFormatter() {}

    /**
     * 创建带 WorldLogger AI 前缀的最终聊天组件。
     *
     * @param rawText AI 返回的原始文本或富文本 JSON。
     * @return 可直接发送到玩家聊天栏的 Component。
     */
    public static Component aiMessage(String rawText) {
        MutableComponent message = Component.translatable("text.worldlogger.ai.prefix").withStyle(ChatFormatting.AQUA);
        message.append(Component.literal(" ").withStyle(ChatFormatting.AQUA));
        message.append(body(rawText));
        return message;
    }

    /**
     * 把 AI 正文转换成 Component。
     *
     * @param rawText AI 返回的原始文本。
     * @return 解析后的正文组件。
     */
    private static Component body(String rawText) {
        String normalized = trim(rawText, MAX_TEXT_LENGTH);
        Component jsonComponent = parseJsonComponent(normalized);
        if (jsonComponent != null) {
            return jsonComponent;
        }
        return parseMarkdownFallback(normalized);
    }

    /**
     * 尝试解析 AI 富文本 JSON。
     *
     * @param text AI 原始输出。
     * @return 解析成功返回 Component，失败返回 null。
     */
    private static Component parseJsonComponent(String text) {
        String jsonText = stripJsonFence(text.trim());
        if (!(jsonText.startsWith("{")) && !(jsonText.startsWith("["))) {
            return null;
        }

        try {
            JsonElement root = JsonParser.parseString(jsonText);
            Component parsed;
            if (root.isJsonObject()) {
                JsonObject object = root.getAsJsonObject();
                if (object.has("worldlogger_component")) {
                    parsed = parseElement(object.get("worldlogger_component"), 0, new SegmentCounter());
                    return parsed.getString().isBlank() ? null : parsed;
                }

                // 普通 JSON 对象不能直接当成组件，否则 {"answer":"..."} 会被解析成空组件。
                // 只有明确包含 text 或 extra 的对象，才视为一个富文本片段。
                if (!(object.has("text")) && !(object.has("extra"))) {
                    return null;
                }
            }
            parsed = parseElement(root, 0, new SegmentCounter());
            return parsed.getString().isBlank() ? null : parsed;
        } catch (JsonSyntaxException | IllegalStateException e) {
            return null;
        }
    }

    /**
     * 去掉模型偶尔包上的 ```json 代码块。
     *
     * @param text 原始文本。
     * @return 可能是 JSON 的主体文本。
     */
    private static String stripJsonFence(String text) {
        if (!(text.startsWith("```"))) {
            return text;
        }

        int firstLineEnd = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstLineEnd < 0 || lastFence <= firstLineEnd) {
            return text;
        }
        return text.substring(firstLineEnd + 1, lastFence).trim();
    }

    /**
     * 解析 JSON 元素。
     *
     * @param element JSON 值，可以是字符串、数组或对象。
     * @param depth 当前递归深度。
     * @param counter 片段计数器。
     * @return 对应 Component。
     */
    private static Component parseElement(JsonElement element, int depth, SegmentCounter counter) {
        if (depth > MAX_DEPTH || element == null || element.isJsonNull()) {
            return Component.empty();
        }

        if (element.isJsonPrimitive()) {
            return segment(trim(element.getAsString(), MAX_SEGMENT_TEXT_LENGTH), Style.EMPTY.withColor(ChatFormatting.WHITE), counter);
        }

        if (element.isJsonArray()) {
            MutableComponent combined = Component.empty();
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) {
                if (counter.value >= MAX_SEGMENTS) {
                    break;
                }
                combined.append(parseElement(child, depth + 1, counter));
            }
            return combined;
        }

        if (element.isJsonObject()) {
            return parseObject(element.getAsJsonObject(), depth, counter);
        }

        return Component.empty();
    }

    /**
     * 解析一个片段对象。
     *
     * @param object 片段对象。
     * @param depth 当前递归深度。
     * @param counter 片段计数器。
     * @return 片段组件。
     */
    private static Component parseObject(JsonObject object, int depth, SegmentCounter counter) {
        String text = trim(readString(object, "text"), MAX_SEGMENT_TEXT_LENGTH);
        MutableComponent component = segment(text, styleFrom(object, depth), counter);

        if (object.has("extra") && object.get("extra").isJsonArray()) {
            for (JsonElement child : object.getAsJsonArray("extra")) {
                if (counter.value >= MAX_SEGMENTS) {
                    break;
                }
                component.append(parseElement(child, depth + 1, counter));
            }
        }

        return component;
    }

    /**
     * 创建一个带样式的文本片段。
     *
     * @param text 文本。
     * @param style 样式。
     * @param counter 片段计数器。
     * @return MutableComponent。
     */
    private static MutableComponent segment(String text, Style style, SegmentCounter counter) {
        counter.value++;
        return Component.literal(text).withStyle(style);
    }

    /**
     * 根据 JSON 字段构造样式。
     *
     * @param object 片段对象。
     * @param depth 当前递归深度；hover 解析会用到。
     * @return Minecraft Style。
     */
    private static Style styleFrom(JsonObject object, int depth) {
        Style style = Style.EMPTY.withColor(ChatFormatting.WHITE);
        TextColor color = readColor(readString(object, "color"));
        if (color != null) {
            style = style.withColor(color);
        }

        style = applyBooleanStyle(style, object, "bold");
        style = applyBooleanStyle(style, object, "italic");
        style = applyBooleanStyle(style, object, "underlined");
        style = applyBooleanStyle(style, object, "strikethrough");
        style = applyBooleanStyle(style, object, "obfuscated");
        style = applyFontStyle(style, object);
        style = applyShadowStyle(style, object);

        String insertion = trim(readString(object, "insertion"), MAX_CLICK_VALUE_LENGTH);
        if (!(insertion.isBlank())) {
            style = style.withInsertion(insertion);
        }

        HoverEvent hoverEvent = hoverEvent(object, depth);
        if (hoverEvent != null) {
            style = style.withHoverEvent(hoverEvent);
        }

        ClickEvent clickEvent = clickEvent(object);
        if (clickEvent != null) {
            style = style.withClickEvent(clickEvent);
        }

        return style;
    }

    /**
     * 应用字体样式。
     *
     * @param style 原始样式。
     * @param object 片段对象。
     * @return 应用后的样式。
     */
    private static Style applyFontStyle(Style style, JsonObject object) {
        String font = readString(object, "font");
        if (font.isBlank()) {
            return style;
        }

        Identifier identifier = Identifier.tryParse(font);
        if (identifier == null) {
            return style;
        }
        return style.withFont(new FontDescription.Resource(identifier));
    }

    /**
     * 应用文字阴影样式。
     *
     * @param style 原始样式。
     * @param object 片段对象。
     * @return 应用后的样式。
     */
    private static Style applyShadowStyle(Style style, JsonObject object) {
        Boolean noShadow = readBooleanObject(object, "no_shadow");
        if (Boolean.TRUE.equals(noShadow)) {
            return style.withoutShadow();
        }

        TextColor shadowColor = readColor(readString(object, "shadow_color"));
        if (shadowColor == null) {
            return style;
        }
        return style.withShadowColor(shadowColor.getValue());
    }

    /**
     * 按字段名应用布尔样式。
     *
     * @param style 原始样式。
     * @param object 片段对象。
     * @param key 样式字段名。
     * @return 应用后的样式。
     */
    private static Style applyBooleanStyle(Style style, JsonObject object, String key) {
        Boolean value = readBooleanObject(object, key);
        if (value == null) {
            return style;
        }

        return switch (key) {
            case "bold" -> style.withBold(value);
            case "italic" -> style.withItalic(value);
            case "underlined" -> style.withUnderlined(value);
            case "strikethrough" -> style.withStrikethrough(value);
            case "obfuscated" -> style.withObfuscated(value);
            default -> style;
        };
    }

    /**
     * 读取颜色字段。
     *
     * @param colorName 颜色名或 #RRGGBB。
     * @return TextColor，非法时返回 null。
     */
    private static TextColor readColor(String colorName) {
        if (colorName.isBlank()) {
            return null;
        }

        TextColor parsed = TextColor.parseColor(colorName).result().orElse(null);
        if (parsed != null) {
            return parsed;
        }

        ChatFormatting formatting = ChatFormatting.getByName(colorName.toLowerCase(Locale.ROOT));
        if (formatting != null && formatting.isColor()) {
            return TextColor.fromLegacyFormat(formatting);
        }
        return null;
    }

    /**
     * 构造 hover 事件。
     *
     * @param object 片段对象。
     * @param depth 当前递归深度。
     * @return HoverEvent，缺失时返回 null。
     */
    private static HoverEvent hoverEvent(JsonObject object, int depth) {
        if (!(object.has("hover_text"))) {
            return null;
        }

        JsonElement hoverText = object.get("hover_text");
        Component hoverComponent;
        if (hoverText != null && (hoverText.isJsonArray() || hoverText.isJsonObject())) {
            hoverComponent = parseElement(hoverText, depth + 1, new SegmentCounter());
        } else {
            hoverComponent = Component.literal(trim(readElementString(hoverText), MAX_HOVER_TEXT_LENGTH)).withStyle(ChatFormatting.GRAY);
        }

        return new HoverEvent.ShowText(hoverComponent);
    }

    /**
     * 构造 click 事件。
     *
     * @param object 片段对象。
     * @return ClickEvent，缺失或不安全时返回 null。
     */
    private static ClickEvent clickEvent(JsonObject object) {
        if (!(object.has("click")) || !(object.get("click").isJsonObject())) {
            return null;
        }

        JsonObject click = object.getAsJsonObject("click");
        String action = readString(click, "action").toLowerCase(Locale.ROOT);
        String value = trim(readString(click, "value"), MAX_CLICK_VALUE_LENGTH);
        if (action.isBlank() || value.isBlank()) {
            return null;
        }

        return switch (action) {
            case "open_url" -> openUrl(value);
            case "suggest_command" -> new ClickEvent.SuggestCommand(value);
            case "copy_to_clipboard" -> new ClickEvent.CopyToClipboard(value);
            case "change_page" -> changePage(value);
            case "run_command" -> safeRunCommand(value);
            default -> null;
        };
    }

    /**
     * 创建打开链接事件。
     *
     * @param value URL 字符串。
     * @return 安全 URL 对应的 ClickEvent。
     */
    private static ClickEvent openUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                return new ClickEvent.OpenUrl(uri);
            }
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        return null;
    }

    /**
     * 创建翻书页事件。
     *
     * @param value 页码文本。
     * @return ClickEvent。
     */
    private static ClickEvent changePage(String value) {
        try {
            return new ClickEvent.ChangePage(Math.max(1, Integer.parseInt(value)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 只允许 AI 运行 WorldLogger 自己的安全查看命令。
     *
     * @param command 命令文本。
     * @return 允许时返回 RunCommand，否则返回 null。
     */
    private static ClickEvent safeRunCommand(String command) {
        if (command.startsWith("/worldlogger select ")
                || command.startsWith("/worldlogger search ")
                || "/worldlogger search".equals(command)
                || "/worldlogger gui".equals(command)) {
            return new ClickEvent.RunCommand(command);
        }
        return null;
    }

    /**
     * JSON 解析失败时的 Markdown 兜底。
     *
     * @param text 原始文本。
     * @return 解析后的组件。
     */
    private static Component parseMarkdownFallback(String text) {
        MutableComponent output = Component.empty();
        int index = 0;
        while (index < text.length()) {
            if (text.startsWith("**", index)) {
                int end = text.indexOf("**", index + 2);
                if (end > index + 2) {
                    output.append(Component.literal(text.substring(index + 2, end)).withStyle(style -> style.withColor(ChatFormatting.WHITE).withBold(true)));
                    index = end + 2;
                    continue;
                }
            }

            if (text.charAt(index) == '`') {
                int end = text.indexOf('`', index + 1);
                if (end > index + 1) {
                    output.append(Component.literal(text.substring(index + 1, end)).withStyle(style -> style.withColor(ChatFormatting.GRAY)));
                    index = end + 1;
                    continue;
                }
            }

            if (text.charAt(index) == '*' && index + 1 < text.length() && !Character.isWhitespace(text.charAt(index + 1))) {
                int end = text.indexOf('*', index + 1);
                if (end > index + 1) {
                    output.append(Component.literal(text.substring(index + 1, end)).withStyle(style -> style.withColor(ChatFormatting.WHITE).withItalic(true)));
                    index = end + 1;
                    continue;
                }
            }

            int next = nextMarkdownToken(text, index + 1);
            output.append(Component.literal(text.substring(index, next)).withStyle(ChatFormatting.WHITE));
            index = next;
        }
        return output;
    }

    /**
     * 查找下一个 Markdown 标记位置。
     *
     * @param text 文本。
     * @param start 起始下标。
     * @return 下一个标记位置或文本长度。
     */
    private static int nextMarkdownToken(String text, int start) {
        int next = text.length();
        int bold = text.indexOf("**", start);
        int code = text.indexOf('`', start);
        int italic = text.indexOf('*', start);
        if (bold >= 0) {
            next = Math.min(next, bold);
        }
        if (code >= 0) {
            next = Math.min(next, code);
        }
        if (italic >= 0) {
            next = Math.min(next, italic);
        }
        return next;
    }

    /**
     * 读取字符串字段。
     *
     * @param object JSON 对象。
     * @param key 字段名。
     * @return 字符串；缺失或类型错误时返回空字符串。
     */
    private static String readString(JsonObject object, String key) {
        if (object == null || !(object.has(key)) || object.get(key).isJsonNull()) {
            return "";
        }
        return readElementString(object.get(key));
    }

    /**
     * 把 JSON 值转成字符串。
     *
     * @param element JSON 值。
     * @return 字符串。
     */
    private static String readElementString(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        try {
            return element.getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * 读取可空布尔字段。
     *
     * @param object JSON 对象。
     * @param key 字段名。
     * @return Boolean；缺失或类型错误时返回 null，让样式继承默认值。
     */
    private static Boolean readBooleanObject(JsonObject object, String key) {
        if (object == null || !(object.has(key)) || object.get(key).isJsonNull()) {
            return null;
        }
        try {
            return object.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 裁剪字符串。
     *
     * @param value 原始值。
     * @param maxLength 最大长度。
     * @return 裁剪后的值。
     */
    private static String trim(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    /** 片段计数器。 */
    private static final class SegmentCounter {
        /** 已经创建的片段数量。 */
        private int value;
    }
}
