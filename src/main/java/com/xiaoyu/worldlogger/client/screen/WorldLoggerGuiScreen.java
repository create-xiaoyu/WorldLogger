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

/**
 * WorldLogger 数据库查看 GUI。
 *
 * <p>这个界面是纯客户端 Screen，不是 Minecraft 容器菜单。
 * 它不会直接访问数据库，而是在表名、页码或搜索词变化时发送网络包给服务器查询。</p>
 *
 * <p>requestId 很重要：数据库查询是异步的，旧请求可能比新请求更晚返回。
 * GUI 只接受 requestId 等于当前 activeRequestId 的响应，从而避免旧数据覆盖新界面。</p>
 */
public class WorldLoggerGuiScreen extends Screen {
    /** 整个屏幕的半透明黑色背景。 */
    private static final int BACKGROUND = 0xE0101010;

    /** 普通面板背景色。 */
    private static final int PANEL = 0xD0202020;

    /** 顶部和底部栏背景色。 */
    private static final int PANEL_DARK = 0xE0181818;

    /** 边框颜色。 */
    private static final int BORDER = 0xFF707070;

    /** 主要文字颜色。 */
    private static final int TEXT = 0xFFE8E8E8;

    /** 次要文字颜色，例如页码和占位提示。 */
    private static final int MUTED_TEXT = 0xFFAAAAAA;

    /** 强调色，例如表列表标题和字段名。 */
    private static final int ACCENT = 0xFFFFD36A;

    /** 错误文字颜色。 */
    private static final int ERROR = 0xFFFF7070;

    /** 左侧表列表的目标宽度。小屏幕会在 leftWidth() 中自动缩小。 */
    private static final int LEFT_WIDTH = 210;

    /** 顶部栏高度。 */
    private static final int HEADER_HEIGHT = 64;

    /** 底部栏高度。 */
    private static final int FOOTER_HEIGHT = 46;

    /** 通用外边距。 */
    private static final int MARGIN = 12;

    /** 左侧表列表每一行按钮高度。 */
    private static final int TABLE_ROW_HEIGHT = 20;

    /** 表列表行之间的间距。 */
    private static final int TABLE_ROW_GAP = 3;

    /** 滚动条宽度。 */
    private static final int SCROLLBAR_WIDTH = 5;

    /** 左侧最多显示的表行数，超过后通过滚动条滚动。 */
    private static final int MAX_VISIBLE_TABLE_ROWS = 8;

    /** 搜索框输入后等待多少 tick 再请求，避免每输入一个字符都立即查数据库。 */
    private static final int SEARCH_DELAY_TICKS = 8;

    /** 所有可查询表名，来自 ListData 白名单。 */
    private final List<String> tables = Arrays.asList(ListData.getTables());

    /** 当前选中的表名。默认选第一张表；如果没有表则为空。 */
    private String selectedTable = tables.isEmpty() ? "" : tables.getFirst();

    /** 搜索框当前过滤词。 */
    private String filter = "";

    /** 当前页码，从 1 开始。 */
    private int page = 1;

    /** 当前最新请求编号。每发一次请求都会 +1。 */
    private int activeRequestId;

    /** 搜索延迟倒计时。-1 表示当前没有等待中的搜索请求。 */
    private int searchDelayTicks = -1;

    /** 右侧数据区域的垂直滚动偏移。 */
    private int scrollOffset;

    /** 右侧数据区域最大可滚动偏移。 */
    private int maxScroll;

    /** 右侧数据完整内容高度。 */
    private int dataContentHeight;

    /** 右侧数据可视区域高度。 */
    private int dataViewportHeight;

    /** 左侧表列表的滚动偏移。 */
    private int tableScrollOffset;

    /** 左侧表列表最大可滚动偏移。 */
    private int tableMaxScroll;

    /** 左侧表列表完整内容高度。 */
    private int tableContentHeight;

    /** 左侧表列表可视区域高度。 */
    private int tableViewportHeight;

    /** true 表示正在拖动右侧数据滚动条。 */
    private boolean draggingDataScrollbar;

    /** true 表示正在拖动左侧表列表滚动条。 */
    private boolean draggingTableScrollbar;

    /** true 表示已经发过至少一次请求，避免 init 多次调用时重复首屏请求。 */
    private boolean requestedOnce;

    /** true 表示当前正在等待服务器返回数据。 */
    private boolean loading;

    /** true 表示服务器告诉客户端还有下一页。 */
    private boolean hasNext;

    /** 查询失败时要显示的错误消息；为 null 表示没有错误。 */
    private Component errorMessage;

    /** 当前页的一条数据库记录，每个 QueryCell 是一列。 */
    private List<GuiTableResponsePayload.QueryCell> cells = List.of();

    /** 搜索输入框。 */
    private EditBox searchBox;

    /** 上一页按钮。 */
    private Button previousButton;

    /** 下一页按钮。 */
    private Button nextButton;

    /** 创建 GUI，并设置界面标题语言 key。 */
    public WorldLoggerGuiScreen() {
        super(Component.translatable("text.worldlogger.gui.title"));
    }

    /** 打开 GUI。调用时必须在客户端线程。 */
    public static void open() {
        Minecraft.getInstance().setScreen(new WorldLoggerGuiScreen());
    }

    /**
     * 接收服务器返回的 GUI 查询结果。
     *
     * @param payload 查询响应。
     */
    public static void handleResponse(GuiTableResponsePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        // 如果当前界面就是 WorldLogger GUI，则交给该界面实例处理。
        if (minecraft.screen instanceof WorldLoggerGuiScreen screen) {
            screen.receiveResponse(payload);
        // 如果界面已经关闭但响应是错误，也把错误发到聊天栏，避免静默失败。
        } else if (!(payload.success()) && minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.translatable(payload.messageKey(), payload.messageArgs().toArray()));
        }
    }

    /**
     * 初始化界面控件。
     *
     * <p>Screen 初始化可能在窗口尺寸变化时再次调用，所以这里要保留已有状态，例如 filter 和 page。</p>
     */
    @Override
    protected void init() {
        // 搜索框放在顶部中间。
        searchBox = new EditBox(font, searchBoxX(), 36, searchBoxWidth(), 20, Component.translatable("text.worldlogger.gui.search"));
        searchBox.setMaxLength(256);
        // 允许搜索框失去焦点，否则 placeholder 和键盘输入体验会不自然。
        searchBox.setCanLoseFocus(true);
        searchBox.setValue(filter);
        searchBox.setResponder(value -> {
            // setResponder 在 setValue 时也可能触发；值没变就不重新请求。
            if (value.equals(filter)) {
                return;
            }

            // 修改搜索词后回到第一页，并启动延迟请求。
            filter = value;
            page = 1;
            searchDelayTicks = SEARCH_DELAY_TICKS;
        });
        addRenderableWidget(searchBox);

        // 底部上一页按钮。
        previousButton = Button.builder(Component.translatable("text.worldlogger.gui.previous"), pressed -> changePage(-1))
                .bounds(MARGIN, height - 34, 120, 20)
                .build();
        // 底部下一页按钮。
        nextButton = Button.builder(Component.translatable("text.worldlogger.gui.next"), pressed -> changePage(1))
                .bounds(width - MARGIN - 120, height - 34, 120, 20)
                .build();
        addRenderableWidget(previousButton);
        addRenderableWidget(nextButton);

        updateButtons();
        // 第一次打开 GUI 时请求默认表第一页。
        if (!(requestedOnce)) {
            requestData();
        }
    }

    /** 初始不让搜索框获得焦点，这样占位提示文字能正常显示。 */
    @Override
    protected void setInitialFocus() {
        clearFocus();
    }

    /** 每 tick 更新搜索延迟倒计时。 */
    @Override
    public void tick() {
        super.tick();
        // searchDelayTicks >= 0 表示有搜索请求等待发送。
        if (searchDelayTicks >= 0) {
            searchDelayTicks--;
            if (searchDelayTicks < 0) {
                requestData();
            }
        }
    }

    /**
     * 渲染界面。
     *
     * @param graphics 绘图对象。
     * @param mouseX 鼠标 X。
     * @param mouseY 鼠标 Y。
     * @param partialTick 局部 tick，当前界面没有用到。
     */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // 计算右侧数据面板的位置和大小。
        graphics.fill(0, 0, width, height, BACKGROUND);

        int leftWidth = leftWidth();
        int contentX = contentX();
        int contentY = HEADER_HEIGHT + 12;
        int contentWidth = contentWidth();
        int contentHeight = Math.max(80, height - contentY - FOOTER_HEIGHT - MARGIN);

        // 绘制顶部栏、底部栏、分隔线和数据面板。
        graphics.fill(0, 0, width, HEADER_HEIGHT, PANEL_DARK);
        graphics.fill(0, height - FOOTER_HEIGHT, width, height, PANEL_DARK);
        graphics.verticalLine(leftWidth, HEADER_HEIGHT, height - FOOTER_HEIGHT, BORDER);
        graphics.horizontalLine(0, width, HEADER_HEIGHT, BORDER);
        graphics.horizontalLine(0, width, height - FOOTER_HEIGHT, BORDER);

        graphics.fill(contentX, contentY, contentX + contentWidth, contentY + contentHeight, PANEL);
        graphics.outline(contentX, contentY, contentWidth, contentHeight, BORDER);

        // 先让父类渲染按钮和搜索框。
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        // 搜索框 placeholder 需要手动绘制，因为 EditBox 当前版本没有合适的内置 placeholder。
        renderSearchPlaceholder(graphics);
        renderTableList(graphics, mouseX, mouseY);

        // 标题和底部分页状态。
        graphics.centeredText(font, title, width / 2, 10, TEXT);
        graphics.centeredText(font, Component.translatable("text.worldlogger.gui.page", selectedTable, page), width / 2, height - 28, MUTED_TEXT);

        // 绘制右侧数据。
        renderData(graphics, contentX, contentY, contentWidth, contentHeight);
    }

    /** 鼠标滚轮事件：根据鼠标所在区域滚动数据区或表列表。 */
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

    /** 鼠标点击事件：处理搜索框失焦、滚动条拖动和表选择。 */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // 点击搜索框外部时清除焦点，让占位提示可以重新显示。
        if (event.button() == 0 && searchBox != null && !(searchBox.isMouseOver(event.x(), event.y()))) {
            clearFocus();
        }

        // 点击右侧滚动条时开始拖动。
        if (event.button() == 0 && isInDataScrollbar(event.x(), event.y()) && maxScroll > 0) {
            draggingDataScrollbar = true;
            updateDataScrollFromMouse(event.y());
            return true;
        }

        // 点击左侧表列表滚动条时开始拖动。
        if (event.button() == 0 && isInTableScrollbar(event.x(), event.y()) && tableMaxScroll > 0) {
            draggingTableScrollbar = true;
            updateTableScrollFromMouse(event.y());
            return true;
        }

        // 点击表列表时按鼠标位置计算表索引。
        if (event.button() == 0 && isInTableList(event.x(), event.y())) {
            selectTableAt(event.x(), event.y());
            return true;
        }

        return super.mouseClicked(event, doubleClick);
    }

    /** 鼠标拖动事件：拖动滚动条时更新滚动偏移。 */
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

    /** 鼠标松开时结束滚动条拖动。 */
    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingDataScrollbar || draggingTableScrollbar) {
            draggingDataScrollbar = false;
            draggingTableScrollbar = false;
            return true;
        }

        return super.mouseReleased(event);
    }

    /**
     * 选择表。
     *
     * @param table 要切换到的表名。
     */
    private void selectTable(String table) {
        // 选择同一张表不需要重新请求。
        if (table.equals(selectedTable)) {
            return;
        }

        // 切换表后回到第一页，并清空旧数据。
        selectedTable = table;
        page = 1;
        scrollOffset = 0;
        cells = List.of();
        requestData();
    }

    /**
     * 翻页。
     *
     * @param delta -1 表示上一页，1 表示下一页。
     */
    private void changePage(int delta) {
        int nextPage = page + delta;
        // 页码不能小于 1。
        if (nextPage < 1) {
            return;
        }
        // 如果服务器说没有下一页，就禁止继续向后翻。
        if (delta > 0 && !(hasNext)) {
            return;
        }

        // 翻页后滚动条回到顶部。
        page = nextPage;
        scrollOffset = 0;
        requestData();
    }

    /** 发送 GUI 查询请求到服务器。 */
    private void requestData() {
        requestedOnce = true;
        searchDelayTicks = -1;
        loading = true;
        hasNext = false;
        errorMessage = null;
        cells = List.of();
        scrollOffset = 0;
        updateButtons();
        // requestId 自增，客户端可以忽略慢查询返回的旧响应。
        ClientPacketDistributor.sendToServer(new GuiTableRequestPayload(selectedTable, page, filter, ++activeRequestId));
    }

    /**
     * 接收并应用 GUI 查询响应。
     *
     * @param payload 服务器响应。
     */
    private void receiveResponse(GuiTableResponsePayload payload) {
        // 旧请求响应直接丢弃，避免覆盖新查询结果。
        if (payload.requestId() != activeRequestId) {
            return;
        }

        // 更新加载状态、页码、下一页标记和数据。
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

    /** 根据当前页码、加载状态和 hasNext 更新按钮可用状态。 */
    private void updateButtons() {
        if (previousButton != null) {
            previousButton.active = page > 1 && !(loading);
        }
        if (nextButton != null) {
            nextButton.active = hasNext && !(loading);
        }
    }

    /**
     * 渲染右侧数据区域。
     *
     * @param graphics 绘图对象。
     * @param x 数据面板 X。
     * @param y 数据面板 Y。
     * @param width 数据面板宽度。
     * @param height 数据面板高度。
     */
    private void renderData(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        // inner 区域给边框留出内边距。
        int innerX = x + 10;
        int innerY = y + 10;
        int innerWidth = width - 20;
        int innerHeight = height - 20;

        // 加载中时显示 loading，并重置滚动信息。
        if (loading) {
            graphics.centeredText(font, Component.translatable("text.worldlogger.gui.loading"), x + width / 2, y + height / 2 - 4, MUTED_TEXT);
            maxScroll = 0;
            dataContentHeight = 0;
            dataViewportHeight = innerHeight;
            return;
        }

        // 查询失败时显示错误文本。
        if (errorMessage != null) {
            graphics.textWithWordWrap(font, errorMessage, innerX, innerY, innerWidth, ERROR);
            maxScroll = 0;
            dataContentHeight = 0;
            dataViewportHeight = innerHeight;
            return;
        }

        // 没有数据时显示空状态。
        if (cells.isEmpty()) {
            graphics.centeredText(font, Component.translatable("text.worldlogger.gui.empty"), x + width / 2, y + height / 2 - 4, MUTED_TEXT);
            maxScroll = 0;
            dataContentHeight = 0;
            dataViewportHeight = innerHeight;
            return;
        }

        // 把列数据拆成可换行显示的 DisplayLine。
        List<DisplayLine> lines = buildDisplayLines(innerWidth - 12);
        int contentHeight = lines.stream().mapToInt(DisplayLine::height).sum();

        // 更新滚动尺寸。maxScroll = 完整内容高度 - 可视高度。
        dataContentHeight = contentHeight;
        dataViewportHeight = innerHeight;
        maxScroll = Math.max(0, contentHeight - innerHeight);
        scrollOffset = clamp(scrollOffset, 0, maxScroll);

        // scissor 会裁剪绘制区域，防止长文本画到面板外。
        graphics.enableScissor(innerX, innerY, innerX + innerWidth, innerY + innerHeight);
        int drawY = innerY - scrollOffset;
        for (DisplayLine line : lines) {
            // 只绘制可见范围内的行，减少不必要的绘制。
            if (drawY + line.height() >= innerY && drawY <= innerY + innerHeight && line.text() != FormattedCharSequence.EMPTY) {
                graphics.text(font, line.text(), innerX + line.indent(), drawY, line.color());
            }
            drawY += line.height();
        }
        graphics.disableScissor();

        // 内容超出可视区域时显示滚动条。
        if (maxScroll > 0) {
            renderScrollbar(graphics, x + width - 8, innerY, innerHeight, contentHeight);
        }
    }

    /** 搜索框为空且未聚焦时绘制占位提示。 */
    private void renderSearchPlaceholder(GuiGraphicsExtractor graphics) {
        if (searchBox != null && searchBox.getValue().isEmpty() && !(searchBox.isFocused())) {
            graphics.text(font, Component.translatable("text.worldlogger.gui.search.hint"), searchBox.getX() + 6, searchBox.getY() + 6, MUTED_TEXT);
        }
    }

    /** 渲染左侧表列表。 */
    private void renderTableList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // 计算表列表面板和内部列表区域。
        int panelX = MARGIN;
        int panelY = HEADER_HEIGHT + MARGIN;
        int panelWidth = leftWidth() - MARGIN * 2;
        int panelHeight = tablePanelHeight();
        int listX = panelX + 6;
        int listY = panelY + 28;
        int listWidth = panelWidth - 12;
        int listHeight = Math.max(12, panelHeight - 36);

        // 面板背景、边框和标题。
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL);
        graphics.outline(panelX, panelY, panelWidth, panelHeight, BORDER);
        graphics.centeredText(font, Component.translatable("text.worldlogger.gui.tables"), panelX + panelWidth / 2, panelY + 8, ACCENT);

        // 计算表列表滚动范围。
        tableContentHeight = tables.isEmpty() ? 0 : tables.size() * (TABLE_ROW_HEIGHT + TABLE_ROW_GAP) - TABLE_ROW_GAP;
        tableViewportHeight = listHeight;
        tableMaxScroll = Math.max(0, tableContentHeight - tableViewportHeight);
        tableScrollOffset = clamp(tableScrollOffset, 0, tableMaxScroll);

        // 裁剪列表区域，避免表按钮画出面板。
        graphics.enableScissor(listX, listY, listX + listWidth, listY + listHeight);
        int drawY = listY - tableScrollOffset;
        for (String table : tables) {
            if (drawY + TABLE_ROW_HEIGHT >= listY && drawY <= listY + listHeight) {
                // 当前选中表和鼠标悬停表使用不同颜色。
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

        // 表数量超过可视区域时显示表列表滚动条。
        if (tableMaxScroll > 0) {
            renderTableScrollbar(graphics, listX + listWidth - 5, listY, listHeight);
        }
    }

    /**
     * 将当前记录的单元格拆成多行显示文本。
     *
     * @param lineWidth 每行最大宽度。
     * @return DisplayLine 列表。
     */
    private List<DisplayLine> buildDisplayLines(int lineWidth) {
        List<DisplayLine> lines = new ArrayList<>();
        for (GuiTableResponsePayload.QueryCell cell : cells) {
            // 字段名优先使用语言文件本地化，同时显示原始列名方便学生和数据库字段对应。
            Component label = Component.translatableWithFallback(columnTranslationKey(cell.columnName()), cell.columnName())
                    .append(" (" + cell.columnName() + ")");
            for (FormattedCharSequence line : font.split(label, lineWidth)) {
                lines.add(new DisplayLine(line, ACCENT, 0, font.lineHeight + 2));
            }

            // 数据值可能包含换行，先统一换成 \n 后按段落拆分。
            String value = cell.value() == null ? "null" : cell.value();
            String[] paragraphs = value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            for (String paragraph : paragraphs) {
                if (paragraph.isEmpty()) {
                    // 空段落也保留一点高度，让原始文本结构不完全丢失。
                    lines.add(new DisplayLine(FormattedCharSequence.EMPTY, TEXT, 8, font.lineHeight));
                    continue;
                }

                // font.split 会根据像素宽度自动换行。
                for (FormattedCharSequence line : font.split(Component.literal(paragraph), Math.max(30, lineWidth - 8))) {
                    lines.add(new DisplayLine(line, TEXT, 8, font.lineHeight + 1));
                }
            }
            // 每个字段之间留一点空白。
            lines.add(new DisplayLine(FormattedCharSequence.EMPTY, TEXT, 0, 6));
        }
        return lines;
    }

    /** 渲染右侧数据滚动条。 */
    private void renderScrollbar(GuiGraphicsExtractor graphics, int x, int y, int height, int contentHeight) {
        graphics.fill(x, y, x + SCROLLBAR_WIDTH, y + height, 0xFF343434);
        int thumbHeight = dataThumbHeight();
        // thumbY 根据当前滚动偏移在线性范围内换算出来。
        int thumbY = y + (height - thumbHeight) * scrollOffset / Math.max(1, maxScroll);
        graphics.fill(x - 1, thumbY, x + SCROLLBAR_WIDTH + 1, thumbY + thumbHeight, draggingDataScrollbar ? 0xFFC8C8C8 : 0xFF9A9A9A);
    }

    /** 渲染左侧表列表滚动条。 */
    private void renderTableScrollbar(GuiGraphicsExtractor graphics, int x, int y, int height) {
        graphics.fill(x, y, x + SCROLLBAR_WIDTH, y + height, 0xFF343434);
        int thumbHeight = tableThumbHeight();
        int thumbY = y + (height - thumbHeight) * tableScrollOffset / Math.max(1, tableMaxScroll);
        graphics.fill(x - 1, thumbY, x + SCROLLBAR_WIDTH + 1, thumbY + thumbHeight, draggingTableScrollbar ? 0xFFC8C8C8 : 0xFF9A9A9A);
    }

    /** 判断鼠标是否在右侧数据区域内。 */
    private boolean isInDataArea(double mouseX, double mouseY) {
        int contentX = contentX();
        int contentY = HEADER_HEIGHT + 12;
        int contentWidth = contentWidth();
        int contentHeight = Math.max(80, height - contentY - FOOTER_HEIGHT - MARGIN);
        return mouseX >= contentX && mouseX <= contentX + contentWidth
                && mouseY >= contentY && mouseY <= contentY + contentHeight;
    }

    /** 判断鼠标是否在左侧表列表区域内。 */
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

    /** 判断鼠标是否在右侧数据滚动条附近。 */
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

    /** 判断鼠标是否在左侧表列表滚动条附近。 */
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

    /**
     * 根据鼠标点击位置选择表。
     *
     * @param mouseX 鼠标 X。
     * @param mouseY 鼠标 Y。
     */
    private void selectTableAt(double mouseX, double mouseY) {
        // 这里重新计算表列表坐标，保证点击逻辑和渲染逻辑使用同一套布局公式。
        int panelX = MARGIN;
        int panelY = HEADER_HEIGHT + MARGIN;
        int panelWidth = leftWidth() - MARGIN * 2;
        int panelHeight = tablePanelHeight();
        int listX = panelX + 6;
        int listY = panelY + 28;
        int listWidth = panelWidth - 12;
        int listHeight = Math.max(12, panelHeight - 36);
        // 点击滚动条区域不选表。
        if (mouseX > listX + listWidth - 10 || mouseY < listY || mouseY > listY + listHeight) {
            return;
        }

        // rowOffset 是加上滚动偏移后的列表内部 Y 坐标。
        int rowOffset = (int) mouseY - listY + tableScrollOffset;
        int rowPitch = TABLE_ROW_HEIGHT + TABLE_ROW_GAP;
        int index = rowOffset / rowPitch;
        int rowY = rowOffset % rowPitch;
        // rowY 必须落在按钮高度内，不能落在行间距上。
        if (index >= 0 && index < tables.size() && rowY < TABLE_ROW_HEIGHT) {
            selectTable(tables.get(index));
        }
    }

    /** 根据鼠标 Y 坐标更新右侧数据滚动偏移。 */
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

    /** 根据鼠标 Y 坐标更新左侧表列表滚动偏移。 */
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

    /** 计算右侧数据滚动条滑块高度。内容越长，滑块越短，但最小为 18。 */
    private int dataThumbHeight() {
        return Math.max(18, dataViewportHeight * dataViewportHeight / Math.max(dataViewportHeight, dataContentHeight));
    }

    /** 计算左侧表列表滚动条滑块高度。 */
    private int tableThumbHeight() {
        return Math.max(18, tableViewportHeight * tableViewportHeight / Math.max(tableViewportHeight, tableContentHeight));
    }

    /** 计算左侧表列表面板高度。 */
    private int tablePanelHeight() {
        int availableHeight = Math.max(40, height - FOOTER_HEIGHT - HEADER_HEIGHT - MARGIN * 3);
        int visibleRows = Math.min(MAX_VISIBLE_TABLE_ROWS, Math.max(1, tables.size()));
        int rowsHeight = visibleRows * (TABLE_ROW_HEIGHT + TABLE_ROW_GAP) - TABLE_ROW_GAP;
        return Math.min(availableHeight, rowsHeight + 36);
    }

    /** 如果表名太长，就按像素宽度截断并追加省略号。 */
    private String trimmedTableLabel(String label, int maxWidth) {
        return font.width(label) <= maxWidth ? label : font.plainSubstrByWidth(label, maxWidth - font.width("...")) + "...";
    }

    /** 计算左侧区域宽度，小窗口会自动缩小。 */
    private int leftWidth() {
        return Math.min(LEFT_WIDTH, Math.max(160, width / 3));
    }

    /** 计算右侧内容区域 X 坐标。 */
    private int contentX() {
        return leftWidth() + MARGIN;
    }

    /** 计算右侧内容区域宽度。 */
    private int contentWidth() {
        return Math.max(120, width - contentX() - MARGIN);
    }

    /** 计算搜索框宽度。 */
    private int searchBoxWidth() {
        return Math.min(560, Math.max(160, width - MARGIN * 4));
    }

    /** 计算搜索框 X 坐标，使它居中。 */
    private int searchBoxX() {
        return (width - searchBoxWidth()) / 2;
    }

    /** 根据数据库列名生成语言文件 key。 */
    private static String columnTranslationKey(String columnName) {
        return "text.worldlogger.name." + columnName.toLowerCase();
    }

    /** 把 value 限制在 min 和 max 之间。 */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 一行已经排版好的显示文本。
     *
     * @param text Minecraft 已经处理好的字符序列。
     * @param color 颜色。
     * @param indent 左侧缩进。
     * @param height 行高。
     */
    private record DisplayLine(FormattedCharSequence text, int color, int indent, int height) {}
}
