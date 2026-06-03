package com.xiaoyu.worldlogger.network.payload;

import com.xiaoyu.worldlogger.WorldLogger;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public record SearchResponsePayload(
        int page,
        boolean success,
        boolean hasNext,
        boolean truncated,
        List<SearchLine> lines,
        String messageKey,
        List<String> messageArgs
) implements CustomPacketPayload {
    private static final int MAX_LINES = 160;
    private static final int MAX_ARGS = 8;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int MAX_ARG_LENGTH = 1200;

    public static final Type<SearchResponsePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(WorldLogger.MODID, "search_response")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, SearchResponsePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SearchResponsePayload decode(RegistryFriendlyByteBuf buf) {
            int page = buf.readVarInt();
            boolean success = buf.readBoolean();
            boolean hasNext = buf.readBoolean();
            boolean truncated = buf.readBoolean();
            String messageKey = buf.readUtf(MAX_KEY_LENGTH);
            List<String> messageArgs = readArgs(buf);

            int lineCount = buf.readVarInt();
            if (lineCount < 0 || lineCount > MAX_LINES) {
                throw new IllegalArgumentException("Invalid WorldLogger search line count: " + lineCount);
            }

            List<SearchLine> lines = new ArrayList<>(lineCount);
            for (int i = 0; i < lineCount; i++) {
                String translationKey = buf.readUtf(MAX_KEY_LENGTH);
                lines.add(new SearchLine(translationKey, readArgs(buf)));
            }

            return new SearchResponsePayload(page, success, hasNext, truncated, lines, messageKey, messageArgs);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, SearchResponsePayload payload) {
            List<SearchLine> lines = payload.lines().stream()
                    .limit(MAX_LINES)
                    .toList();

            buf.writeVarInt(payload.page());
            buf.writeBoolean(payload.success());
            buf.writeBoolean(payload.hasNext());
            buf.writeBoolean(payload.truncated());
            buf.writeUtf(trimKey(payload.messageKey()), MAX_KEY_LENGTH);
            writeArgs(buf, payload.messageArgs());
            buf.writeVarInt(lines.size());
            for (SearchLine line : lines) {
                buf.writeUtf(trimKey(line.translationKey()), MAX_KEY_LENGTH);
                writeArgs(buf, line.args());
            }
        }
    };

    public SearchResponsePayload {
        page = Math.max(1, page);
        lines = List.copyOf(lines);
        messageKey = messageKey == null ? "" : messageKey;
        messageArgs = List.copyOf(messageArgs);
    }

    public static SearchResponsePayload success(int page, boolean hasNext, boolean truncated, List<SearchLine> lines) {
        return new SearchResponsePayload(page, true, hasNext, truncated, lines, "", List.of());
    }

    public static SearchResponsePayload error(String messageKey, String... messageArgs) {
        return error(1, messageKey, messageArgs);
    }

    public static SearchResponsePayload error(int page, String messageKey, String... messageArgs) {
        return new SearchResponsePayload(page, false, false, false, List.of(), messageKey, List.of(messageArgs));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static List<String> readArgs(RegistryFriendlyByteBuf buf) {
        int argCount = buf.readVarInt();
        if (argCount < 0 || argCount > MAX_ARGS) {
            throw new IllegalArgumentException("Invalid WorldLogger search arg count: " + argCount);
        }

        List<String> args = new ArrayList<>(argCount);
        for (int i = 0; i < argCount; i++) {
            args.add(buf.readUtf(MAX_ARG_LENGTH));
        }
        return args;
    }

    private static void writeArgs(RegistryFriendlyByteBuf buf, List<String> args) {
        List<String> trimmedArgs = args.stream()
                .limit(MAX_ARGS)
                .map(SearchResponsePayload::trimArg)
                .toList();

        buf.writeVarInt(trimmedArgs.size());
        for (String arg : trimmedArgs) {
            buf.writeUtf(arg, MAX_ARG_LENGTH);
        }
    }

    private static String trimKey(String key) {
        if (key.length() <= MAX_KEY_LENGTH) {
            return key;
        }
        return key.substring(0, MAX_KEY_LENGTH);
    }

    private static String trimArg(String arg) {
        if (arg.length() <= MAX_ARG_LENGTH) {
            return arg;
        }
        return arg.substring(0, MAX_ARG_LENGTH - 3) + "...";
    }

    public record SearchLine(String translationKey, List<String> args) {
        public SearchLine {
            args = List.copyOf(args);
        }
    }
}
