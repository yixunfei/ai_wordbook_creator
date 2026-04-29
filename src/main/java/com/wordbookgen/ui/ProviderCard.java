package com.wordbookgen.ui;

import com.wordbookgen.core.model.ProviderConfig;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * Collapsible provider editor card used by the main frame.
 */
class ProviderCard extends JPanel {

    private static final Color CARD_BACKGROUND = Color.WHITE;
    private static final Color BORDER_NORMAL = new Color(215, 223, 233);
    private static final Color BORDER_HOVER = new Color(74, 127, 205);
    private static final Color HEADER_BG = new Color(246, 249, 253);
    private static final int LABEL_WIDTH = 96;
    private static final int SHORT_FIELD_WIDTH = 176;
    private static final int LONG_FIELD_WIDTH = 360;
    private static final int MODEL_FIELD_WIDTH = 248;
    private static final int SPINNER_WIDTH = 92;

    private final JLabel dragHandle = new JLabel("☰ 拖拽排序");
    private final JLabel titleLabel = new JLabel("Provider");
    private final JLabel summaryLabel = new JLabel(" ");
    private final JButton collapseButton = new JButton("收起");
    private final JButton copyButton = new JButton("复制");
    private final JButton deleteButton = new JButton("删除");
    private final JButton testButton = new JButton("连接测试");
    private final JButton fetchModelsButton = new JButton("刷新模型");

    private final JPanel bodyPanel = new JPanel(new GridBagLayout());

    private final JTextField nameField = new JTextField();
    private final JTextField urlField = new JTextField();
    private final JPasswordField apiKeyField = new JPasswordField();
    private final JComboBox<String> modelCombo = new JComboBox<>();
    private final JSpinner concurrencySpinner = new JSpinner(new SpinnerNumberModel(8, 1, 8, 1));
    private final JSpinner quotaSpinner = new JSpinner(new SpinnerNumberModel(1000, 1, 100000, 1));
    private final JSpinner windowSpinner = new JSpinner(new SpinnerNumberModel(300, 1, 10080, 1));

    private Runnable onRemove = () -> {
    };
    private Runnable onCopy = () -> {
    };
    private Runnable onTest = () -> {
    };
    private Runnable onFetchModels = () -> {
    };

    private boolean collapsed = false;
    private boolean dragHover = false;

    ProviderCard(UiProviderItem item) {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createLineBorder(BORDER_NORMAL, 1, true));
        setBackground(CARD_BACKGROUND);
        bodyPanel.setBackground(CARD_BACKGROUND);
        bodyPanel.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));

        dragHandle.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        dragHandle.setForeground(new Color(68, 85, 102));
        summaryLabel.setForeground(new Color(102, 110, 125));
        applyNaturalText();

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBackground(HEADER_BG);
        JPanel identityPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        identityPanel.setOpaque(false);
        identityPanel.add(dragHandle);
        identityPanel.add(titleLabel);
        identityPanel.add(summaryLabel);
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        actionPanel.setOpaque(false);
        actionPanel.add(collapseButton);
        actionPanel.add(copyButton);
        actionPanel.add(deleteButton);
        actionPanel.add(testButton);
        header.add(identityPanel, BorderLayout.CENTER);
        header.add(actionPanel, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        initBody();
        add(bodyPanel, BorderLayout.CENTER);
        modelCombo.setEditable(true);
        modelCombo.setToolTipText("推荐通过“刷新模型”自动发现，必要时可手动输入。");

        fill(item);
        bindActions();
        bindSummarySync();
    }

    private void applyNaturalText() {
        dragHandle.setText("\u62d6\u52a8\u6392\u5e8f");
        collapseButton.setText("\u6536\u8d77");
        copyButton.setText("\u590d\u5236");
        deleteButton.setText("\u5220\u9664");
        testButton.setText("\u8fde\u63a5\u6d4b\u8bd5");
        fetchModelsButton.setText("\u5237\u65b0\u6a21\u578b");
    }

    JLabel getDragHandle() {
        return dragHandle;
    }

    void setOnRemove(Runnable onRemove) {
        this.onRemove = onRemove == null ? () -> {
        } : onRemove;
    }

    void setOnCopy(Runnable onCopy) {
        this.onCopy = onCopy == null ? () -> {
        } : onCopy;
    }

    void setOnTest(Runnable onTest) {
        this.onTest = onTest == null ? () -> {
        } : onTest;
    }

    void setOnFetchModels(Runnable onFetchModels) {
        this.onFetchModels = onFetchModels == null ? () -> {
        } : onFetchModels;
    }

    void setDragHover(boolean hover) {
        if (this.dragHover == hover) {
            return;
        }
        this.dragHover = hover;
        setBorder(BorderFactory.createLineBorder(hover ? BORDER_HOVER : BORDER_NORMAL, hover ? 2 : 1, true));
    }

    void setEditable(boolean editable) {
        nameField.setEditable(editable);
        urlField.setEditable(editable);
        apiKeyField.setEditable(editable);
        modelCombo.setEnabled(editable);
        fetchModelsButton.setEnabled(editable);
        concurrencySpinner.setEnabled(editable);
        quotaSpinner.setEnabled(editable);
        windowSpinner.setEnabled(editable);
        collapseButton.setEnabled(editable);
        copyButton.setEnabled(editable);
        deleteButton.setEnabled(editable);
        testButton.setEnabled(editable);
    }

    void setTesting(boolean testing) {
        testButton.setEnabled(!testing);
        testButton.setText(testing ? "测试中..." : "连接测试");
    }

    void setModelFetching(boolean fetching) {
        fetchModelsButton.setEnabled(!fetching);
        fetchModelsButton.setText(fetching ? "拉取中..." : "刷新模型");
    }

    void replaceModelItems(List<String> models) {
        String current = currentModel();
        modelCombo.removeAllItems();
        for (String model : models) {
            modelCombo.addItem(model);
        }
        if (current != null && !current.isBlank()) {
            modelCombo.setSelectedItem(current);
        } else if (modelCombo.getItemCount() > 0) {
            modelCombo.setSelectedIndex(0);
        }
        updateSummary();
    }

    UiProviderItem toUiProviderItem() {
        UiProviderItem item = new UiProviderItem();
        item.name = nameField.getText().trim();
        item.url = urlField.getText().trim();
        item.apiKey = readApiKey();
        item.model = currentModel();
        item.concurrency = (int) concurrencySpinner.getValue();
        item.quota = (int) quotaSpinner.getValue();
        item.windowMinutes = (int) windowSpinner.getValue();
        return item;
    }

    ProviderConfig toProviderConfig() {
        String name = required(nameField.getText(), "name");
        String url = required(urlField.getText(), "url");
        String apiKey = required(readApiKey(), "apiKey");
        String model = required(currentModel(), "model");
        return new ProviderConfig(
                name,
                URI.create(url),
                apiKey,
                model,
                (int) concurrencySpinner.getValue(),
                (int) quotaSpinner.getValue(),
                (int) windowSpinner.getValue());
    }

    ProviderConfig toProviderConfigForModelDiscovery() {
        String model = currentModel();
        if (model == null || model.isBlank()) {
            model = "placeholder-model";
        }
        return new ProviderConfig(
                required(nameField.getText(), "name"),
                URI.create(required(urlField.getText(), "url")),
                required(readApiKey(), "apiKey"),
                model,
                (int) concurrencySpinner.getValue(),
                (int) quotaSpinner.getValue(),
                (int) windowSpinner.getValue());
    }

    private void initBody() {
        setPreferredWidth(nameField, SHORT_FIELD_WIDTH);
        setPreferredWidth(apiKeyField, SHORT_FIELD_WIDTH);
        setPreferredWidth(urlField, LONG_FIELD_WIDTH);
        setPreferredWidth(modelCombo, MODEL_FIELD_WIDTH);
        setPreferredWidth(concurrencySpinner, SPINNER_WIDTH);
        setPreferredWidth(quotaSpinner, SPINNER_WIDTH);
        setPreferredWidth(windowSpinner, SPINNER_WIDTH);

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 5, 4, 5);
        c.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        addField(bodyPanel, c, row, 0, "\u540d\u79f0", nameField);
        addField(bodyPanel, c, row, 2, "\u63a5\u53e3\u5730\u5740", urlField);
        addRowFiller(bodyPanel, c, row);

        row++;
        JPanel modelPanel = new JPanel(new BorderLayout(6, 0));
        modelPanel.setOpaque(false);
        modelPanel.add(modelCombo, BorderLayout.CENTER);
        modelPanel.add(fetchModelsButton, BorderLayout.EAST);
        addField(bodyPanel, c, row, 0, "API Key", apiKeyField);
        addField(bodyPanel, c, row, 2, "\u6a21\u578b", modelPanel);
        addRowFiller(bodyPanel, c, row);

        row++;
        JPanel right = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        right.setOpaque(false);
        right.add(quotaSpinner);
        right.add(new JLabel("/"));
        right.add(windowSpinner);
        addField(bodyPanel, c, row, 0, "\u5e76\u53d1", concurrencySpinner);
        addField(bodyPanel, c, row, 2, "\u989d\u5ea6 / \u7a97\u53e3(\u5206)", right);
        addRowFiller(bodyPanel, c, row);
    }

    private void addField(
            JPanel panel,
            GridBagConstraints c,
            int row,
            int column,
            String labelText,
            java.awt.Component component
    ) {
        c.gridy = row;
        c.gridx = column;
        c.weightx = 0;
        panel.add(fixedLabel(labelText), c);
        c.gridx = column + 1;
        panel.add(component, c);
    }

    private void addRowFiller(JPanel panel, GridBagConstraints c, int row) {
        JPanel filler = new JPanel();
        filler.setOpaque(false);
        c.gridy = row;
        c.gridx = 4;
        c.weightx = 1;
        panel.add(filler, c);
        c.weightx = 0;
    }

    private JLabel fixedLabel(String text) {
        JLabel label = new JLabel(text);
        Dimension size = new Dimension(LABEL_WIDTH, label.getPreferredSize().height);
        label.setPreferredSize(size);
        label.setMinimumSize(size);
        label.setHorizontalAlignment(JLabel.RIGHT);
        return label;
    }

    private void setPreferredWidth(javax.swing.JComponent component, int width) {
        Dimension preferred = component.getPreferredSize();
        component.setPreferredSize(new Dimension(width, preferred.height));
    }

    private void bindActions() {
        collapseButton.addActionListener(e -> toggleCollapse());
        copyButton.addActionListener(e -> onCopy.run());
        deleteButton.addActionListener(e -> onRemove.run());
        testButton.addActionListener(e -> onTest.run());
        fetchModelsButton.addActionListener(e -> onFetchModels.run());
        modelCombo.addActionListener(e -> updateSummary());
    }

    private void bindSummarySync() {
        DocumentListener listener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateSummary();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateSummary();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateSummary();
            }
        };
        nameField.getDocument().addDocumentListener(listener);
        urlField.getDocument().addDocumentListener(listener);
    }

    private void toggleCollapse() {
        collapsed = !collapsed;
        bodyPanel.setVisible(!collapsed);
        collapseButton.setText(collapsed ? "展开" : "收起");
        revalidate();
        repaint();
    }

    private void fill(UiProviderItem item) {
        UiProviderItem source = item == null ? new UiProviderItem() : item;
        nameField.setText(source.name == null ? "" : source.name);
        urlField.setText(source.url == null ? "" : source.url);
        apiKeyField.setText(source.apiKey == null ? "" : source.apiKey);
        modelCombo.removeAllItems();
        modelCombo.addItem(source.model == null || source.model.isBlank() ? "" : source.model);
        modelCombo.setSelectedItem(source.model == null ? "" : source.model);
        concurrencySpinner.setValue(Math.max(1, Math.min(8, source.concurrency <= 0 ? 8 : source.concurrency)));
        quotaSpinner.setValue(source.quota <= 0 ? 1000 : source.quota);
        windowSpinner.setValue(source.windowMinutes <= 0 ? 300 : source.windowMinutes);
        updateSummary();
    }

    private void updateSummary() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        String model = currentModel();
        String url = urlField.getText() == null ? "" : urlField.getText().trim();
        titleLabel.setText(name.isBlank() ? "Provider" : trimMiddle(name, 24));
        titleLabel.setToolTipText(name.isBlank() ? null : name);
        summaryLabel.setText("[" + (model.isBlank() ? "no-model" : model) + "] " + trimMiddle(url, 36));
        summaryLabel.setToolTipText(url.isBlank() ? null : url);
    }

    private String trimMiddle(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text == null ? "" : text;
        }
        int head = Math.max(6, maxLen / 2 - 2);
        int tail = Math.max(6, maxLen - head - 3);
        return text.substring(0, head) + "..." + text.substring(text.length() - tail);
    }

    private String currentModel() {
        Object selected = modelCombo.getEditor().getItem();
        if (selected == null) {
            selected = modelCombo.getSelectedItem();
        }
        return selected == null ? "" : selected.toString().trim();
    }

    private String readApiKey() {
        char[] chars = apiKeyField.getPassword();
        if (chars == null || chars.length == 0) {
            return "";
        }
        try {
            return new String(chars).trim();
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    private String required(String value, String field) {
        String ret = value == null ? "" : value.trim();
        if (ret.isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return ret;
    }
}
