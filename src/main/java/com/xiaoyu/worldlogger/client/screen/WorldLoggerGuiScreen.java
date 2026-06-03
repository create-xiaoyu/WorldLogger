package com.xiaoyu.worldlogger.client.screen;

import com.xiaoyu.worldlogger.data.ListData;
import com.xiaoyu.worldlogger.network.payload.GuiTableRequestPayload;
import com.xiaoyu.worldlogger.network.payload.GuiTableResponsePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class WorldLoggerGuiScreen extends Screen {
    private static final int BACKGROUND = 0xE0101010;
    private static final int PANEL = 0xD0202020;
    private static final int PANEL_DARK = 0xE0181818;
    private static final int BORDER = 0xFF707070;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int MUTED_TEXT = 0xFFAAAAAA;
    private static final int ACCENT = 0xFFFFD36A;
    private static final int ERROR = 0xFFFF7070;
    private static final int LEFT_WIDTH = 210;
    private static final int HEADER_HEIGHT = 64;
    private static final int FOOTER_HEIGHT = 46;
    private static final int MARGIN = 12;
    private static final int TABLE_ROW_HEIGHT = 20;
    private static final int TABLE_ROW_GAP = 3;
    private static final int SCROLLBAR_WIDTH = 5;
    private static final int MAX_VISIBLE_TABLE_ROWS = 8;
    private static final int SEARCH_DELAY_TICKS = 8;

    private final List<String> tables = Arrays.asList(ListData.getTables());

    private String selectedTable = tables.isEmpty() ? "" : tables.getFirst();
    private String filter = "";
    private int page = 1;
    private int activeRequestId;
    private int searchDelayTicks = -1;
    private int scrollOffset;
    private int maxScroll;
    private int dataContentHeight;
    private int dataViewportHeight;
    private int tableScrollOffset;
    private int tableMaxScroll;
    private int tableContentHeight;
    private int tableViewportHeight;
    private boolean draggingDataScrollbar;
    private boolean draggingTableScrollbar;
    private boolean requestedOnce;
    private boolean loading;
    private boolean hasNext;
    private Component errorMessage;
    private List<GuiTableResponsePayload.QueryCell> cells = List.of();

    private EditBox searchBox;
    private Button previousButton;
    private Button nextButton;

    public WorldLoggerGuiScreen() {
        super(Component.translatable("text.worldlogger.gui.title"));
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new WorldLoggerGuiScreen());
    }

    public static void handleResponse(GuiTableResponsePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof WorldLoggerGuiScreen screen) {
            screen.receiveResponse(payload);
        } else if (!(payload.success()) && minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.translatable(payload.messageKey(), payload.messageArgs().toArray()));
        }
    }

    @Override
    protected void init() {
        searchBox = new EditBox(font, searchBoxX(), 36, searchBoxWidth(), 20, Component.translatable("text.worldlogger.gui.search"));
        searchBox.setMaxLength(256);
        searchBox.setCanLoseFocus(true);
        searchBox.setHint(Component.translatable("text.worldlogger.gui.search.hint"));
        searchBox.setValue(filter);
        searchBox.setResponder(value -> {
            if (value.equals(filter)) {
                return;
            }

            filter = value;
            page = 1;
            searchDelayTicks = SEARCH_DELAY_TICKS;
        });
        addRenderableWidget(searchBox);

        previousButton = Button.builder(Component.translatable("text.worldlogger.gui.previous"), pressed -> changePage(-1))
                .bounds(MARGIN, height - 34, 120, 20)
                .build();
        nextButton = Button.builder(Component.translatable("text.worldlogger.gui.next"), pressed -> changePage(1))
                .bounds(width - MARGIN - 120, height - 34, 120, 20)
                .build();
        addRenderableWidget(previousButton);
        addRenderableWidget(nextButton);

        updateButtons();
        if (!(requestedOnce)) {
            requestData();
        }
    }

    @Override
    protected void setInitialFocus() {
        clearFocus();
    }

    @Override
    public void tick() {
        super.tick();
        if (searchDelayTicks >= 0) {
            searchDelayTicks--;
            if (searchDelayTicks < 0) {
                requestData();
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BACKGROUND);

        int leftWidth = leftWidth();
        int contentX = contentX();
        int contentY = HEADER_HEIGHT + 12;
        int contentWidth = contentWidth();
        int contentHeight = Math.max(80, height - contentY - FOOTER_HEIGHT - MARGIN);

        graphics.fill(0, 0, width, HEADER_HEIGHT, PANEL_DARK);
        graphics.fill(0, height - FOOTER_HEIGHT, width, height, PANEL_DARK);
        graphics.verticalLine(leftWidth, HEADER_HEIGHT, height - FOOTER_HEIGHT, BORDER);
        graphics.horizontalLine(0, width, HEADER_HEIGHT, BORDER);
        graphics.horizontalLine(0, width, height - FOOTER_HEIGHT, BORDER);

        graphics.fill(contentX, contentY, contentX + contentWidth, contentY + contentHeight, PANEL);
        graphics.outline(contentX, contentY, contentWidth, contentHeight, BORDER);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        renderTableList(graphics, mouseX, mouseY);

        graphics.centeredText(font, title, width / 2, 10, TEXT);
        graphics.centeredText(font, Component.translatable("text.worldlogger.gui.page", selectedTable, page), width / 2, height - 28, MUTED_TEXT);

        renderData(graphics, contentX, contentY, contentWidth, contentHeight);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isInDataArea(mouseX, mouseY) && maxScroll > 0) {
            scrollOffset = clamp(scrollOffset - (int) (scrollY * 18), 0, maxScroll);
            return true;
        }
        if (isInTableList(mouseX, mouseY) && tableMaxScroll > 0) {
            tableScrollOffset = clamp(tableScrollOffset - (int) (scrollY * 18), 0, tableMaxScroll);
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && searchBox != null && !(searchBox.isMouseOver(event.x(), event.y()))) {
            clearFocus();
        }

        if (event.button() == 0 && isInDataScrollbar(event.x(), event.y()) && maxScroll > 0) {
            draggingDataScrollbar = true;
            updateDataScrollFromMouse(event.y());
            return true;
        }

        if (event.button() == 0 && isInTableScrollbar(event.x(), event.y()) && tableMaxScroll > 0) {
            draggingTableScrollbar = true;
            updateTableScrollFromMouse(event.y());
            return true;
        }

        if (event.button() == 0 && isInTableList(event.x(), event.y())) {
            selectTableAt(event.x(), event.y());
            return true;
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingDataScrollbar) {
            updateDataScrollFromMouse(event.y());
            return true;
        }
        if (draggingTableScrollbar) {
            updateTableScrollFromMouse(event.y());
            return true;
        }

        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingDataScrollbar || draggingTableScrollbar) {
            draggingDataScrollbar = false;
            draggingTableScrollbar = false;
            return true;
        }

        return super.mouseReleased(event);
    }

    private void selectTable(String table) {
        if (table.equals(selectedTable)) {
            return;
        }

        selectedTable = table;
        page = 1;
        scrollOffset = 0;
        cells = List.of();
        requestData();
    }

    private void changePage(int delta) {
        int nextPage = page + delta;
        if (nextPage < 1) {
            return;
        }
        if (delta > 0 && !(hasNext)) {
            return;
        }

        page = nextPage;
        scrollOffset = 0;
        requestData();
    }

    private void requestData() {
        requestedOnce = true;
        searchDelayTicks = -1;
        loading = true;
        hasNext = false;
        errorMessage = null;
        cells = List.of();
        scrollOffset = 0;
        updateButtons();
        ClientPacketDistributor.sendToServer(new GuiTableRequestPayload(selectedTable, page, filter, ++activeRequestId));
    }

    private void receiveResponse(GuiTableResponsePayload payload) {
        if (payload.requestId() != activeRequestId) {
            return;
        }

        loading = false;
        hasNext = payload.hasNext();
        page = payload.page();
        if (payload.success()) {
            errorMessage = null;
            cells = payload.cells();
        } else {
            cells = List.of();
            errorMessage = Component.translatable(payload.messageKey(), payload.messageArgs().toArray());
        }
        scrollOffset = 0;
        updateButtons();
    }

    private void updateButtons() {
        if (previousButton != null) {
            previousButton.active = page > 1 && !(loading);
        }
        if (nextButton != null) {
            nextButton.active = hasNext && !(loading);
        }
    }

    private void renderData(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        int innerX = x + 10;
        int innerY = y + 10;
        int innerWidth = width - 20;
        int innerHeight = height - 20;

        if (loading) {
            graphics.centeredText(font, Component.translatable("text.worldlogger.gui.loading"), x + width / 2, y + height / 2 - 4, MUTED_TEXT);
            maxScroll = 0;
            dataContentHeight = 0;
            dataViewportHeight = innerHeight;
            return;
        }

        if (errorMessage != null) {
            graphics.textWithWordWrap(font, errorMessage, innerX, innerY, innerWidth, ERROR);
            maxScroll = 0;
            dataContentHeight = 0;
            dataViewportHeight = innerHeight;
            return;
        }

        if (cells.isEmpty()) {
            graphics.centeredText(font, Component.translatable("text.worldlogger.gui.empty"), x + width / 2, y + height / 2 - 4, MUTED_TEXT);
            maxScroll = 0;
            dataContentHeight = 0;
            dataViewportHeight = innerHeight;
            return;
        }

        List<DisplayLine> lines = buildDisplayLines(innerWidth - 12);
        int contentHeight = lines.stream().mapToInt(DisplayLine::height).sum();
        dataContentHeight = contentHeight;
        dataViewportHeight = innerHeight;
        maxScroll = Math.max(0, contentHeight - innerHeight);
        scrollOffset = clamp(scrollOffset, 0, maxScroll);

        graphics.enableScissor(innerX, innerY, innerX + innerWidth, innerY + innerHeight);
        int drawY = innerY - scrollOffset;
        for (DisplayLine line : lines) {
            if (drawY + line.height() >= innerY && drawY <= innerY + innerHeight && line.text() != FormattedCharSequence.EMPTY) {
                graphics.text(font, line.text(), innerX + line.indent(), drawY, line.color());
            }
            drawY += line.height();
        }
        graphics.disableScissor();

        if (maxScroll > 0) {
            renderScrollbar(graphics, x + width - 8, innerY, innerHeight, contentHeight);
        }
    }

    private void renderTableList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int panelX = MARGIN;
        int panelY = HEADER_HEIGHT + MARGIN;
        int panelWidth = leftWidth() - MARGIN * 2;
        int panelHeight = tablePanelHeight();
        int listX = panelX + 6;
        int listY = panelY + 28;
        int listWidth = panelWidth - 12;
        int listHeight = Math.max(12, panelHeight - 36);

        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL);
        graphics.outline(panelX, panelY, panelWidth, panelHeight, BORDER);
        graphics.centeredText(font, Component.translatable("text.worldlogger.gui.tables"), panelX + panelWidth / 2, panelY + 8, ACCENT);

        tableContentHeight = tables.isEmpty() ? 0 : tables.size() * (TABLE_ROW_HEIGHT + TABLE_ROW_GAP) - TABLE_ROW_GAP;
        tableViewportHeight = listHeight;
        tableMaxScroll = Math.max(0, tableContentHeight - tableViewportHeight);
        tableScrollOffset = clamp(tableScrollOffset, 0, tableMaxScroll);

        graphics.enableScissor(listX, listY, listX + listWidth, listY + listHeight);
        int drawY = listY - tableScrollOffset;
        for (String table : tables) {
            if (drawY + TABLE_ROW_HEIGHT >= listY && drawY <= listY + listHeight) {
                boolean selected = table.equals(selectedTable);
                boolean hovered = mouseX >= listX && mouseX <= listX + listWidth - 8
                        && mouseY >= drawY && mouseY <= drawY + TABLE_ROW_HEIGHT;
                int fillColor = selected ? 0xFF686868 : hovered ? 0xFF484848 : 0xFF303030;
                int textColor = selected ? TEXT : 0xFFD8D8D8;
                graphics.fill(listX, drawY, listX + listWidth - 8, drawY + TABLE_ROW_HEIGHT, fillColor);
                graphics.outline(listX, drawY, listWidth - 8, TABLE_ROW_HEIGHT, selected ? 0xFFEFEFEF : 0xFF505050);
                graphics.text(font, trimmedTableLabel(selected ? "> " + table : table, listWidth - 18), listX + 5, drawY + 6, textColor);
            }
            drawY += TABLE_ROW_HEIGHT + TABLE_ROW_GAP;
        }
        graphics.disableScissor();

        if (tableMaxScroll > 0) {
            renderTableScrollbar(graphics, listX + listWidth - 5, listY, listHeight);
        }
    }

    private List<DisplayLine> buildDisplayLines(int lineWidth) {
        List<DisplayLine> lines = new ArrayList<>();
        for (GuiTableResponsePayload.QueryCell cell : cells) {
            Component label = Component.translatableWithFallback(columnTranslationKey(cell.columnName()), cell.columnName())
                    .append(" (" + cell.columnName() + ")");
            for (FormattedCharSequence line : font.split(label, lineWidth)) {
                lines.add(new DisplayLine(line, ACCENT, 0, font.lineHeight + 2));
            }

            String value = cell.value() == null ? "null" : cell.value();
            String[] paragraphs = value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            for (String paragraph : paragraphs) {
                if (paragraph.isEmpty()) {
                    lines.add(new DisplayLine(FormattedCharSequence.EMPTY, TEXT, 8, font.lineHeight));
                    continue;
                }

                for (FormattedCharSequence line : font.split(Component.literal(paragraph), Math.max(30, lineWidth - 8))) {
                    lines.add(new DisplayLine(line, TEXT, 8, font.lineHeight + 1));
                }
            }
            lines.add(new DisplayLine(FormattedCharSequence.EMPTY, TEXT, 0, 6));
        }
        return lines;
    }

    private void renderScrollbar(GuiGraphicsExtractor graphics, int x, int y, int height, int contentHeight) {
        graphics.fill(x, y, x + SCROLLBAR_WIDTH, y + height, 0xFF343434);
        int thumbHeight = dataThumbHeight();
        int thumbY = y + (height - thumbHeight) * scrollOffset / Math.max(1, maxScroll);
        graphics.fill(x - 1, thumbY, x + SCROLLBAR_WIDTH + 1, thumbY + thumbHeight, draggingDataScrollbar ? 0xFFC8C8C8 : 0xFF9A9A9A);
    }

    private void renderTableScrollbar(GuiGraphicsExtractor graphics, int x, int y, int height) {
        graphics.fill(x, y, x + SCROLLBAR_WIDTH, y + height, 0xFF343434);
        int thumbHeight = tableThumbHeight();
        int thumbY = y + (height - thumbHeight) * tableScrollOffset / Math.max(1, tableMaxScroll);
        graphics.fill(x - 1, thumbY, x + SCROLLBAR_WIDTH + 1, thumbY + thumbHeight, draggingTableScrollbar ? 0xFFC8C8C8 : 0xFF9A9A9A);
    }

    private boolean isInDataArea(double mouseX, double mouseY) {
        int contentX = contentX();
        int contentY = HEADER_HEIGHT + 12;
        int contentWidth = contentWidth();
        int contentHeight = Math.max(80, height - contentY - FOOTER_HEIGHT - MARGIN);
        return mouseX >= contentX && mouseX <= contentX + contentWidth
                && mouseY >= contentY && mouseY <= contentY + contentHeight;
    }

    private boolean isInTableList(double mouseX, double mouseY) {
        int panelX = MARGIN;
        int panelY = HEADER_HEIGHT + MARGIN;
        int panelWidth = leftWidth() - MARGIN * 2;
        int panelHeight = tablePanelHeight();
        int listX = panelX + 6;
        int listY = panelY + 28;
        int listWidth = panelWidth - 12;
        int listHeight = Math.max(12, panelHeight - 36);
        return mouseX >= listX && mouseX <= listX + listWidth
                && mouseY >= listY && mouseY <= listY + listHeight;
    }

    private boolean isInDataScrollbar(double mouseX, double mouseY) {
        int contentX = contentX();
        int contentY = HEADER_HEIGHT + 12;
        int contentWidth = contentWidth();
        int contentHeight = Math.max(80, height - contentY - FOOTER_HEIGHT - MARGIN);
        int innerY = contentY + 10;
        int innerHeight = contentHeight - 20;
        int scrollbarX = contentX + contentWidth - 8;
        return mouseX >= scrollbarX - 4 && mouseX <= scrollbarX + SCROLLBAR_WIDTH + 4
                && mouseY >= innerY && mouseY <= innerY + innerHeight;
    }

    private boolean isInTableScrollbar(double mouseX, double mouseY) {
        int panelX = MARGIN;
        int panelY = HEADER_HEIGHT + MARGIN;
        int panelWidth = leftWidth() - MARGIN * 2;
        int panelHeight = tablePanelHeight();
        int listX = panelX + 6;
        int listY = panelY + 28;
        int listWidth = panelWidth - 12;
        int listHeight = Math.max(12, panelHeight - 36);
        int scrollbarX = listX + listWidth - 5;
        return mouseX >= scrollbarX - 4 && mouseX <= scrollbarX + SCROLLBAR_WIDTH + 4
                && mouseY >= listY && mouseY <= listY + listHeight;
    }

    private void selectTableAt(double mouseX, double mouseY) {
        int panelX = MARGIN;
        int panelY = HEADER_HEIGHT + MARGIN;
        int panelWidth = leftWidth() - MARGIN * 2;
        int panelHeight = tablePanelHeight();
        int listX = panelX + 6;
        int listY = panelY + 28;
        int listWidth = panelWidth - 12;
        int listHeight = Math.max(12, panelHeight - 36);
        if (mouseX > listX + listWidth - 10 || mouseY < listY || mouseY > listY + listHeight) {
            return;
        }

        int rowOffset = (int) mouseY - listY + tableScrollOffset;
        int rowPitch = TABLE_ROW_HEIGHT + TABLE_ROW_GAP;
        int index = rowOffset / rowPitch;
        int rowY = rowOffset % rowPitch;
        if (index >= 0 && index < tables.size() && rowY < TABLE_ROW_HEIGHT) {
            selectTable(tables.get(index));
        }
    }

    private void updateDataScrollFromMouse(double mouseY) {
        int contentY = HEADER_HEIGHT + 12;
        int contentHeight = Math.max(80, height - contentY - FOOTER_HEIGHT - MARGIN);
        int innerY = contentY + 10;
        int innerHeight = contentHeight - 20;
        int thumbHeight = dataThumbHeight();
        int movableHeight = Math.max(1, innerHeight - thumbHeight);
        int target = (int) Math.round((mouseY - innerY - thumbHeight / 2.0) * maxScroll / movableHeight);
        scrollOffset = clamp(target, 0, maxScroll);
    }

    private void updateTableScrollFromMouse(double mouseY) {
        int panelY = HEADER_HEIGHT + MARGIN;
        int panelHeight = tablePanelHeight();
        int listY = panelY + 28;
        int listHeight = Math.max(12, panelHeight - 36);
        int thumbHeight = tableThumbHeight();
        int movableHeight = Math.max(1, listHeight - thumbHeight);
        int target = (int) Math.round((mouseY - listY - thumbHeight / 2.0) * tableMaxScroll / movableHeight);
        tableScrollOffset = clamp(target, 0, tableMaxScroll);
    }

    private int dataThumbHeight() {
        return Math.max(18, dataViewportHeight * dataViewportHeight / Math.max(dataViewportHeight, dataContentHeight));
    }

    private int tableThumbHeight() {
        return Math.max(18, tableViewportHeight * tableViewportHeight / Math.max(tableViewportHeight, tableContentHeight));
    }

    private int tablePanelHeight() {
        int availableHeight = Math.max(40, height - FOOTER_HEIGHT - HEADER_HEIGHT - MARGIN * 3);
        int visibleRows = Math.min(MAX_VISIBLE_TABLE_ROWS, Math.max(1, tables.size()));
        int rowsHeight = visibleRows * (TABLE_ROW_HEIGHT + TABLE_ROW_GAP) - TABLE_ROW_GAP;
        return Math.min(availableHeight, rowsHeight + 36);
    }

    private String trimmedTableLabel(String label, int maxWidth) {
        return font.width(label) <= maxWidth ? label : font.plainSubstrByWidth(label, maxWidth - font.width("...")) + "...";
    }

    private int leftWidth() {
        return Math.min(LEFT_WIDTH, Math.max(160, width / 3));
    }

    private int contentX() {
        return leftWidth() + MARGIN;
    }

    private int contentWidth() {
        return Math.max(120, width - contentX() - MARGIN);
    }

    private int searchBoxWidth() {
        return Math.min(560, Math.max(160, width - MARGIN * 4));
    }

    private int searchBoxX() {
        return (width - searchBoxWidth()) / 2;
    }

    private static String columnTranslationKey(String columnName) {
        return "text.worldlogger.name." + columnName.toLowerCase();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record DisplayLine(FormattedCharSequence text, int color, int indent, int height) {}
}
