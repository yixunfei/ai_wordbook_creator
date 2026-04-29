package com.wordbookgen.ui;

import com.wordbookgen.core.JobListener;
import com.wordbookgen.core.WordbookJobEngine;
import com.wordbookgen.core.model.JobConfig;
import com.wordbookgen.core.model.JobState;
import com.wordbookgen.core.model.OutputFormat;
import com.wordbookgen.core.model.ProgressSnapshot;
import com.wordbookgen.core.model.ProviderConfig;
import com.wordbookgen.core.model.ScriptHookConfig;
import com.wordbookgen.core.model.ScriptLanguage;
import com.wordbookgen.core.provider.ModelDiscoveryClient;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 主界面。
 * 包含任务配置、Provider 卡片管理、运行控制以及高级设置。
 */
public class WordbookGenFrame extends JFrame {

    private static final int DEFAULT_BATCH_SIZE = 12;
    private static final int DEFAULT_TIMEOUT_SEC = 180;
    private static final int LOG_PANEL_EXPANDED_HEIGHT = 180;
    private static final int LOG_PANEL_COLLAPSED_HEIGHT = 48;
    private static final int MAIN_LABEL_WIDTH = 118;
    private static final int PATH_FIELD_WIDTH = 560;
    private static final int OPTION_FIELD_WIDTH = 144;
    private static final Color APP_BACKGROUND = new Color(244, 247, 251);
    private static final Color CARD_BACKGROUND = Color.WHITE;
    private static final Color SOFT_BORDER = new Color(218, 226, 236);

    private final WordbookJobEngine engine = new WordbookJobEngine();
    private final UiConfigStore configStore = new UiConfigStore();
    private final ProviderConnectivityTester connectivityTester = new ProviderConnectivityTester();
    private final ModelDiscoveryClient modelDiscoveryClient = new ModelDiscoveryClient();

    private final JTextArea promptArea = new JTextArea(3, 60);
    private final JTextField inputPathField = new JTextField();
    private final JTextField outputPathField = new JTextField();
    private final JTextField configDirectoryField = new JTextField();
    private final JButton browseConfigDirectoryButton = new JButton("\u9009\u62e9");
    private final JComboBox<String> outputFormatCombo = new JComboBox<>(new String[]{"JSON", "CSV"});
    private final JComboBox<String> encodingCombo = new JComboBox<>(new String[]{"UTF-8", "GBK", "UTF-16LE"});
    private final JTextField sourceLangField = new JTextField("English");
    private final JTextField targetLangField = new JTextField("Chinese");
    private final JSpinner batchSizeSpinner = new JSpinner(new SpinnerNumberModel(DEFAULT_BATCH_SIZE, 1, 1000, 1));
    private final JSpinner parallelismSpinner = new JSpinner(new SpinnerNumberModel(4, 1, 8, 1));
    private final JSpinner retriesSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 20, 1));
    private final JSpinner timeoutSpinner = new JSpinner(new SpinnerNumberModel(DEFAULT_TIMEOUT_SEC, 10, 600, 5));
    private final JCheckBox resumeCheck = new JCheckBox("断点续传", true);
    private final JCheckBox clearCheckpointCheck = new JCheckBox("成功后清理断点文件", true);

    private final JButton startButton = new JButton("开始");
    private final JButton pauseButton = new JButton("暂停");
    private final JButton resumeButton = new JButton("继续");
    private final JButton stopButton = new JButton("停止");
    private final JButton saveConfigButton = new JButton("保存配置");
    private final JButton loadConfigButton = new JButton("加载配置");
    private final JButton addProviderButton = new JButton("新增 Provider");
    private final JButton advancedSettingsButton = new JButton("高级设置");
    private final JCheckBox autoFetchModelsAfterTestCheck = new JCheckBox("测试成功后自动刷新模型", true);

    private final JCheckBox allowNonStandardResponsesCheck = new JCheckBox(
            "\u517c\u5bb9\u975e\u6807\u51c6 Provider \u8fd4\u56de", true);
    private final JCheckBox autoContinueTruncatedCheck = new JCheckBox(
            "\u8fd4\u56de\u88ab\u622a\u65ad\u65f6\u81ea\u52a8\u7eed\u5199", false);

    private final JTextArea logArea = new JTextArea();
    private final JButton toggleLogPanelButton = new JButton("\u6536\u8d77\u65e5\u5fd7");
    private final JProgressBar progressBar = new JProgressBar();
    private final JLabel statusLabel = new JLabel("状态: IDLE");

    private final JPanel providersListPanel = new JPanel();
    private final List<ProviderCard> providerCards = new ArrayList<>();

    private AdvancedSettingsState advancedSettings = AdvancedSettingsState.defaultState();
    private ProviderCard draggingCard;
    private JSplitPane mainSplitPane;
    private JPanel logPanel;
    private JScrollPane logScrollPane;
    private boolean logPanelCollapsed = false;
    private int lastExpandedDividerLocation = -1;

    public WordbookGenFrame() {
        super("WordbookGen - AI 单词本生成器");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 600));
        adaptWindowSizeToScreen();
        setLocationRelativeTo(null);

        initUi();
        applyNaturalUiText();
        bindActions();
        applySettings(configStore.load().orElseGet(this::defaultSettings));
        updateButtons(JobState.IDLE);
    }

    private void applyNaturalUiText() {
        setTitle("AI Wordbook Creator");
        startButton.setText("\u5f00\u59cb\u751f\u6210");
        pauseButton.setText("\u6682\u505c");
        resumeButton.setText("\u7ee7\u7eed");
        stopButton.setText("\u505c\u6b62");
        saveConfigButton.setText("\u4fdd\u5b58\u914d\u7f6e");
        loadConfigButton.setText("\u52a0\u8f7d\u914d\u7f6e");
        addProviderButton.setText("\u6dfb\u52a0 Provider");
        advancedSettingsButton.setText("\u9ad8\u7ea7\u8bbe\u7f6e");
        resumeCheck.setText("\u65ad\u70b9\u7eed\u4f20");
        clearCheckpointCheck.setText("\u6210\u529f\u540e\u6e05\u7406\u65ad\u70b9\u6587\u4ef6");
        allowNonStandardResponsesCheck.setText("\u517c\u5bb9\u975e\u6807\u51c6 Provider \u8fd4\u56de");
        autoContinueTruncatedCheck.setText("\u8fd4\u56de\u88ab\u622a\u65ad\u65f6\u81ea\u52a8\u7eed\u5199");
        autoFetchModelsAfterTestCheck.setText("\u6d4b\u8bd5\u6210\u529f\u540e\u81ea\u52a8\u5237\u65b0\u6a21\u578b");
        statusLabel.setText("\u72b6\u6001: IDLE");
        resumeCheck.setOpaque(false);
        clearCheckpointCheck.setOpaque(false);
        allowNonStandardResponsesCheck.setOpaque(false);
        autoContinueTruncatedCheck.setOpaque(false);
        autoFetchModelsAfterTestCheck.setOpaque(false);
        toggleLogPanelButton.setText("\u6536\u8d77\u65e5\u5fd7");

        allowNonStandardResponsesCheck.setToolTipText("\u517c\u5bb9\u76f4\u63a5 JSON\u3001output_text \u7b49\u975e\u6807\u51c6\u8fd4\u56de\u3002\u5173\u95ed\u540e\u4ec5\u63a5\u53d7\u6807\u51c6 Chat Completions \u7ed3\u6784\u3002");
        autoContinueTruncatedCheck.setToolTipText("\u9ed8\u8ba4\u5173\u95ed\u3002\u5f53 finish_reason=length \u65f6\u5c1d\u8bd5\u8ffd\u52a0\u8bf7\u6c42\u7eed\u5199\u5269\u4f59 JSON\u3002");
        toggleLogPanelButton.setToolTipText("\u6536\u8d77\u6216\u5c55\u5f00\u5e95\u90e8\u8fd0\u884c\u65e5\u5fd7");
    }

    private void initUi() {
        setLayout(new BorderLayout(8, 8));
        getContentPane().setBackground(APP_BACKGROUND);
        add(createToolbarPanel(), BorderLayout.NORTH);

        mainSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        mainSplitPane.setResizeWeight(0.78);
        mainSplitPane.setContinuousLayout(true);
        mainSplitPane.setOneTouchExpandable(true);

        JScrollPane configScrollPane = new JScrollPane(createConfigContainer());
        configScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        mainSplitPane.setTopComponent(configScrollPane);
        mainSplitPane.setBottomComponent(createNaturalLogPanel());
        add(mainSplitPane, BorderLayout.CENTER);

        SwingUtilities.invokeLater(() -> {
            mainSplitPane.setDividerLocation(0.78);
            applyLogPanelState(false);
            configScrollPane.getViewport().setViewPosition(new Point(0, 0));
        });
    }

    private void adaptWindowSizeToScreen() {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int width = Math.max(920, Math.min((int) (screen.width * 0.86), 1280));
        int height = Math.max(620, Math.min((int) (screen.height * 0.78), 820));
        setSize(new Dimension(width, height));
    }

    private JPanel createToolbarPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(APP_BACKGROUND);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT));
        left.setOpaque(false);
        left.add(startButton);
        left.add(pauseButton);
        left.add(resumeButton);
        left.add(stopButton);
        left.add(Box.createHorizontalStrut(12));
        left.add(advancedSettingsButton);
        left.add(Box.createHorizontalStrut(12));
        left.add(saveConfigButton);
        left.add(loadConfigButton);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        right.setOpaque(false);
        progressBar.setStringPainted(true);
        progressBar.setPreferredSize(new Dimension(240, 24));
        right.add(progressBar);
        right.add(Box.createHorizontalStrut(8));
        right.add(statusLabel);

        panel.add(left, BorderLayout.WEST);
        panel.add(right, BorderLayout.EAST);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
        return panel;
    }

    private JPanel createConfigContainer() {
        JPanel container = new JPanel(new GridBagLayout());
        container.setBackground(APP_BACKGROUND);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 8, 0);

        c.gridy = 0;
        c.weighty = 0;
        container.add(createNaturalProviderPanel(), c);

        c.gridy = 1;
        container.add(createNaturalPathAndOptionsPanel(), c);

        c.gridy = 2;
        c.insets = new Insets(0, 0, 0, 0);
        container.add(createNaturalPromptPanel(), c);

        // 占位行用于把内容顶到上方，避免 BoxLayout 弹性拉伸导致的空白区域。
        c.gridy = 3;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        JPanel filler = new JPanel();
        filler.setBackground(APP_BACKGROUND);
        container.add(filler, c);
        return container;
    }

    private JPanel createNaturalPathAndOptionsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        applySectionStyle(panel, "\u8f93\u5165\u8f93\u51fa\u4e0e\u8fd0\u884c\u9009\u9879");

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.insets = new Insets(5, 8, 5, 8);
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        addFullWidthRow(panel, c, row++, createPathRow("\u8f93\u5165\u5355\u8bcd\u6587\u4ef6", inputPathField,
                new JButton("\u9009\u62e9"), this::chooseInputFile));
        addFullWidthRow(panel, c, row++, createPathRow("\u8f93\u51fa\u6587\u4ef6", outputPathField,
                new JButton("\u9009\u62e9"), this::chooseOutputFile));
        addFullWidthRow(panel, c, row++, createPathRow("\u914d\u7f6e\u6587\u4ef6\u5939", configDirectoryField,
                browseConfigDirectoryButton, this::chooseConfigDirectory));

        addFullWidthRow(panel, c, row++, createOptionRow(
                "\u8f93\u51fa\u683c\u5f0f", outputFormatCombo,
                "\u6587\u4ef6\u7f16\u7801", encodingCombo));
        addFullWidthRow(panel, c, row++, createOptionRow(
                "\u6e90\u8bed\u8a00", sourceLangField,
                "\u91ca\u4e49\u8bed\u8a00", targetLangField));
        addFullWidthRow(panel, c, row++, createOptionRow(
                "\u6279\u5927\u5c0f", batchSizeSpinner,
                "\u5e76\u53d1\u6570", parallelismSpinner));
        addFullWidthRow(panel, c, row++, createOptionRow(
                "\u91cd\u8bd5\u6b21\u6570", retriesSpinner,
                "\u8bf7\u6c42\u8d85\u65f6(\u79d2)", timeoutSpinner));

        JPanel switches = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        switches.setOpaque(false);
        switches.add(resumeCheck);
        switches.add(clearCheckpointCheck);
        switches.add(allowNonStandardResponsesCheck);
        switches.add(autoContinueTruncatedCheck);
        addFullWidthRow(panel, c, row, switches);
        return panel;
    }

    private void addFullWidthRow(JPanel panel, GridBagConstraints c, int row, JPanel rowPanel) {
        c.gridy = row;
        c.weightx = 1;
        panel.add(rowPanel, c);
    }

    private JPanel createPathRow(String label, JTextField field, JButton button, Runnable action) {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        setPreferredWidth(field, PATH_FIELD_WIDTH);

        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.insets = new Insets(0, 0, 0, 8);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        c.weightx = 0;
        row.add(fixedLabel(label), c);
        c.gridx = 1;
        row.add(field, c);
        c.gridx = 2;
        button.addActionListener(e -> action.run());
        row.add(button, c);
        c.gridx = 3;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 0, 0);
        row.add(createTransparentFiller(), c);
        return row;
    }

    private JPanel createOptionRow(
            String leftLabel,
            JComponent leftComponent,
            String rightLabel,
            JComponent rightComponent
    ) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        addOption(row, leftLabel, leftComponent);
        row.add(Box.createHorizontalStrut(12));
        addOption(row, rightLabel, rightComponent);
        return row;
    }

    private void addOption(JPanel row, String label, JComponent component) {
        setPreferredWidth(component, OPTION_FIELD_WIDTH);
        row.add(fixedLabel(label));
        row.add(component);
    }

    private JLabel fixedLabel(String text) {
        JLabel label = new JLabel(text);
        Dimension size = new Dimension(MAIN_LABEL_WIDTH, label.getPreferredSize().height);
        label.setPreferredSize(size);
        label.setMinimumSize(size);
        label.setHorizontalAlignment(SwingConstants.RIGHT);
        return label;
    }

    private void setPreferredWidth(JComponent component, int width) {
        Dimension preferred = component.getPreferredSize();
        component.setPreferredSize(new Dimension(width, preferred.height));
    }

    private JPanel createTransparentFiller() {
        JPanel filler = new JPanel();
        filler.setOpaque(false);
        return filler;
    }

    private JPanel createNaturalProviderPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        applySectionStyle(panel, "Provider \u914d\u7f6e");

        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setOpaque(false);
        topBar.add(new JLabel("\u6309\u987a\u5e8f\u8f6e\u8be2 Provider\uff0c\u53ef\u6d4b\u8bd5\u8fde\u901a\u6027\u5e76\u81ea\u52a8\u5237\u65b0\u6a21\u578b\u5217\u8868"),
                BorderLayout.WEST);
        JPanel rightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightActions.setOpaque(false);
        rightActions.add(autoFetchModelsAfterTestCheck);
        rightActions.add(addProviderButton);
        topBar.add(rightActions, BorderLayout.EAST);
        panel.add(topBar, BorderLayout.NORTH);

        providersListPanel.setLayout(new BoxLayout(providersListPanel, BoxLayout.Y_AXIS));
        providersListPanel.setBackground(CARD_BACKGROUND);
        JScrollPane scrollPane = new JScrollPane(providersListPanel);
        scrollPane.setPreferredSize(new Dimension(10, 240));
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createNaturalPromptPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        applySectionStyle(panel, "\u8865\u5145\u63d0\u793a\u8bcd");
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        panel.add(new JScrollPane(promptArea), BorderLayout.CENTER);
        return panel;
    }

    private JPanel createNaturalLogPanel() {
        logPanel = new JPanel(new BorderLayout(8, 8));
        applySectionStyle(logPanel, "\u8fd0\u884c\u65e5\u5fd7");
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logScrollPane = new JScrollPane(logArea);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JLabel hintLabel = new JLabel("\u53ef\u968f\u65f6\u6536\u8d77\uff0c\u4fdd\u6301\u5e95\u90e8\u89c6\u56fe\u7b80\u6d01");
        hintLabel.setForeground(new Color(108, 116, 130));
        header.add(hintLabel, BorderLayout.WEST);
        header.add(toggleLogPanelButton, BorderLayout.EAST);

        logPanel.add(header, BorderLayout.NORTH);
        logPanel.add(logScrollPane, BorderLayout.CENTER);
        logPanel.setPreferredSize(new Dimension(10, LOG_PANEL_EXPANDED_HEIGHT));
        return logPanel;
    }

    private void applySectionStyle(JPanel panel, String title) {
        panel.setBackground(CARD_BACKGROUND);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(BorderFactory.createLineBorder(SOFT_BORDER), title),
                BorderFactory.createEmptyBorder(4, 6, 6, 6)));
    }

    private void toggleLogPanel() {
        if (!logPanelCollapsed && mainSplitPane != null) {
            lastExpandedDividerLocation = mainSplitPane.getDividerLocation();
        }
        logPanelCollapsed = !logPanelCollapsed;
        applyLogPanelState(true);
    }

    private void applyLogPanelState(boolean adjustDivider) {
        if (logPanel == null || logScrollPane == null) {
            return;
        }

        logScrollPane.setVisible(!logPanelCollapsed);
        toggleLogPanelButton.setText(logPanelCollapsed ? "\u5c55\u5f00\u65e5\u5fd7" : "\u6536\u8d77\u65e5\u5fd7");
        logPanel.setPreferredSize(new Dimension(
                10,
                logPanelCollapsed ? LOG_PANEL_COLLAPSED_HEIGHT : LOG_PANEL_EXPANDED_HEIGHT));
        logPanel.revalidate();
        logPanel.repaint();

        if (!adjustDivider || mainSplitPane == null) {
            return;
        }

        SwingUtilities.invokeLater(() -> {
            if (logPanelCollapsed) {
                int target = Math.max(
                        140,
                        mainSplitPane.getHeight() - LOG_PANEL_COLLAPSED_HEIGHT - mainSplitPane.getDividerSize());
                mainSplitPane.setDividerLocation(target);
                return;
            }

            // Prefer restoring the user's previous expanded height before falling back to the default size.
            if (lastExpandedDividerLocation > 0 && lastExpandedDividerLocation < mainSplitPane.getHeight()) {
                mainSplitPane.setDividerLocation(lastExpandedDividerLocation);
                return;
            }

            int target = Math.max(
                    140,
                    mainSplitPane.getHeight() - LOG_PANEL_EXPANDED_HEIGHT - mainSplitPane.getDividerSize());
            mainSplitPane.setDividerLocation(target);
        });
    }

    private void bindActions() {
        startButton.addActionListener(e -> onStart());
        pauseButton.addActionListener(e -> engine.pause());
        resumeButton.addActionListener(e -> engine.resume());
        stopButton.addActionListener(e -> engine.stop());
        saveConfigButton.addActionListener(e -> onSaveConfig());
        loadConfigButton.addActionListener(e -> onLoadConfig());
        addProviderButton.addActionListener(e -> addProviderCard(defaultProviderItem(), providerCards.size()));
        advancedSettingsButton.addActionListener(e -> openAdvancedSettingsDialog());
        toggleLogPanelButton.addActionListener(e -> toggleLogPanel());
    }

    private void onStart() {
        try {
            JobConfig config = buildConfigFromUi();
            appendLog("[配置] batchSize=" + config.batchSize()
                    + ", parallelism=" + config.parallelism()
                    + ", timeoutSec=" + config.requestTimeout().toSeconds()
                    + ", debugMode=" + config.debugMode()
                    + ", allowNonStandardResponses=" + config.allowNonStandardResponses()
                    + ", autoContinueTruncatedOutput=" + config.autoContinueTruncatedOutput()
                    + ", providers=" + config.providers().size());
            engine.start(config, uiListener());
            appendLog("任务已启动。");
        } catch (Exception ex) {
            showError("启动失败: " + ex.getMessage());
        }
    }

    private void onSaveConfig() {
        try {
            UiSettings settings = collectUiSettings();
            Path configDirectory = selectedConfigDirectory();
            configStore.save(settings, configDirectory);
            appendLog("配置已保存: " + configStore.settingsPathForDirectory(configDirectory));
        } catch (Exception ex) {
            showError("保存配置失败: " + ex.getMessage());
        }
    }

    private void onLoadConfig() {
        Path configDirectory = selectedConfigDirectory();
        Optional<UiSettings> loaded = configStore.load(configDirectory);
        if (loaded.isEmpty()) {
            showError("未找到已保存配置。路径: " + configStore.settingsPathForDirectory(configDirectory));
            return;
        }
        applySettings(loaded.get());
        configDirectoryField.setText(configDirectory.toAbsolutePath().normalize().toString());
        appendLog("配置已加载: " + configStore.settingsPathForDirectory(configDirectory));
    }

    private JobConfig buildConfigFromUi() {
        if (providerCards.isEmpty()) {
            throw new IllegalArgumentException("请至少配置一个 provider。");
        }

        List<ProviderConfig> providers = new ArrayList<>();
        for (int i = 0; i < providerCards.size(); i++) {
            ProviderCard card = providerCards.get(i);
            try {
                providers.add(card.toProviderConfig());
            } catch (Exception ex) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 个 provider 配置有误: " + ex.getMessage(), ex);
            }
        }

        ScriptHookConfig preHook = toScriptHookConfig(advancedSettings.preRequestHook);
        ScriptHookConfig postRespHook = toScriptHookConfig(advancedSettings.postResponseHook);
        ScriptHookConfig postParsedHook = toScriptHookConfig(advancedSettings.postParsedHook);

        Path input = Path.of(requireText(inputPathField.getText(), "请输入输入文件路径"));
        Path output = Path.of(requireText(outputPathField.getText(), "请输入输出文件路径"));
        OutputFormat format = OutputFormat.fromText((String) outputFormatCombo.getSelectedItem());
        Charset charset = Charset.forName((String) encodingCombo.getSelectedItem());
        int batchSize = (int) batchSizeSpinner.getValue();
        int parallelism = (int) parallelismSpinner.getValue();
        int maxRetries = (int) retriesSpinner.getValue();
        int timeoutSec = (int) timeoutSpinner.getValue();

        return JobConfig.builder()
                .providers(providers)
                .customPrompt(promptArea.getText())
                .inputPath(input)
                .outputPath(output)
                .checkpointPath(Path.of(output + ".checkpoint.json"))
                .outputFormat(format)
                .sourceLanguage(normalizeText(sourceLangField.getText(), "English"))
                .targetLanguage(normalizeText(targetLangField.getText(), "Chinese"))
                .encoding(charset)
                .batchSize(batchSize)
                .parallelism(parallelism)
                .maxRetries(maxRetries)
                .requestTimeout(Duration.ofSeconds(timeoutSec))
                .initialBackoff(Duration.ofMillis(1200))
                .maxBackoff(Duration.ofSeconds(20))
                .resumeFromCheckpoint(resumeCheck.isSelected())
                .clearCheckpointOnSuccess(clearCheckpointCheck.isSelected())
                .allowNonStandardResponses(allowNonStandardResponsesCheck.isSelected())
                .autoContinueTruncatedOutput(autoContinueTruncatedCheck.isSelected())
                .useSystemPromptOverride(advancedSettings.useSystemPromptOverride)
                .systemPromptTemplate(advancedSettings.systemPromptTemplate)
                .preRequestHook(preHook)
                .postResponseHook(postRespHook)
                .postParsedHook(postParsedHook)
                .debugMode(advancedSettings.debugMode)
                .build();
    }

    private ScriptHookConfig toScriptHookConfig(UiScriptHookItem item) {
        if (item == null) {
            return ScriptHookConfig.disabled();
        }
        return new ScriptHookConfig(
                item.enabled,
                ScriptLanguage.fromText(item.language),
                item.scriptPath == null ? "" : item.scriptPath.trim(),
                item.timeoutSec <= 0 ? 30 : item.timeoutSec
        );
    }

    private void addProviderCard(UiProviderItem item, int targetIndex) {
        ProviderCard card = new ProviderCard(item);
        card.setOnRemove(() -> removeProviderCard(card));
        card.setOnCopy(() -> copyProviderCard(card));
        card.setOnTest(() -> testProviderConnection(card));
        card.setOnFetchModels(() -> fetchModelsForCard(card));
        attachDragHandlers(card);

        int index = Math.max(0, Math.min(targetIndex, providerCards.size()));
        providerCards.add(index, card);
        refreshProvidersPanel();
    }

    private void removeProviderCard(ProviderCard card) {
        if (providerCards.size() <= 1) {
            showError("至少保留一个 provider。");
            return;
        }
        providerCards.remove(card);
        refreshProvidersPanel();
    }

    private void copyProviderCard(ProviderCard card) {
        int index = providerCards.indexOf(card);
        UiProviderItem item = card.toUiProviderItem();
        addProviderCard(item, index + 1);
    }

    private void fetchModelsForCard(ProviderCard card) {
        fetchModelsForCard(card, true);
    }

    private void fetchModelsForCard(ProviderCard card, boolean popupOnError) {
        ProviderConfig config;
        try {
            config = card.toProviderConfigForModelDiscovery();
        } catch (Exception ex) {
            String message = "Provider 配置不完整，无法拉取模型: " + ex.getMessage();
            appendLog("[模型] " + message);
            if (popupOnError) {
                showError(message);
            }
            return;
        }

        int timeoutSec = (int) timeoutSpinner.getValue();
        card.setModelFetching(true);
        appendLog("[模型] provider=" + config.name() + " 开始拉取模型列表...");

        Thread thread = new Thread(() -> {
            try {
                List<String> models = modelDiscoveryClient.discover(config, Duration.ofSeconds(timeoutSec));
                SwingUtilities.invokeLater(() -> {
                    card.setModelFetching(false);
                    card.replaceModelItems(models);
                    appendLog("[模型] provider=" + config.name() + " 拉取成功，数量=" + models.size());
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    card.setModelFetching(false);
                    appendLog("[模型] provider=" + config.name() + " 拉取失败: " + ex.getMessage());
                    if (popupOnError) {
                        showError("拉取模型失败: " + ex.getMessage());
                    }
                });
            }
        }, "model-discovery-" + config.name());
        thread.setDaemon(true);
        thread.start();
    }

    private void testProviderConnection(ProviderCard card) {
        ProviderConfig config;
        try {
            config = card.toProviderConfig();
        } catch (Exception ex) {
            showError("Provider 配置不完整: " + ex.getMessage());
            return;
        }

        int timeoutSec = (int) timeoutSpinner.getValue();
        card.setTesting(true);
        appendLog("[测试] provider=" + config.name() + " 开始连接测试...");

        Thread thread = new Thread(() -> {
            ProviderConnectivityTester.TestResult result = connectivityTester.test(config, Duration.ofSeconds(timeoutSec));
            SwingUtilities.invokeLater(() -> {
                card.setTesting(false);
                String message = "[测试] provider=" + config.name() + " => " + result.message();
                appendLog(message);

                String title = result.success() ? "连接测试成功" : "连接测试失败";
                int type = result.success() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE;
                showMessageDialog(title, message, type);

                if (result.success() && autoFetchModelsAfterTestCheck.isSelected()) {
                    appendLog("[模型] 连接测试成功，自动刷新模型列表...");
                    fetchModelsForCard(card, false);
                }
            });
        }, "provider-test-" + config.name());
        thread.setDaemon(true);
        thread.start();
    }

    private void attachDragHandlers(ProviderCard card) {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                draggingCard = card;
                highlightDropTarget(providerCards.indexOf(card));
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (draggingCard == null) {
                    return;
                }
                Point dragPoint = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), providersListPanel);
                int insertIndex = calculateDropIndex(dragPoint.y);
                highlightDropTarget(insertIndex);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (draggingCard == null) {
                    return;
                }
                Point dropPoint = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), providersListPanel);
                int from = providerCards.indexOf(draggingCard);
                int to = calculateDropIndex(dropPoint.y);
                moveCard(from, to);
                draggingCard = null;
                clearDragPreview();
            }
        };
        card.getDragHandle().addMouseListener(adapter);
        card.getDragHandle().addMouseMotionListener(adapter);
    }

    private int calculateDropIndex(int yInPanel) {
        if (providerCards.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < providerCards.size(); i++) {
            ProviderCard card = providerCards.get(i);
            int middle = card.getY() + card.getHeight() / 2;
            if (yInPanel < middle) {
                return i;
            }
        }
        return providerCards.size();
    }

    private void moveCard(int from, int to) {
        if (from < 0 || from >= providerCards.size()) {
            return;
        }
        int insertionIndex = Math.max(0, Math.min(to, providerCards.size()));
        if (insertionIndex == from || insertionIndex == from + 1) {
            return;
        }
        ProviderCard card = providerCards.remove(from);
        if (insertionIndex > from) {
            insertionIndex--;
        }
        insertionIndex = Math.max(0, Math.min(insertionIndex, providerCards.size()));
        providerCards.add(insertionIndex, card);
        refreshProvidersPanel();
    }

    private void highlightDropTarget(int insertionIndex) {
        int targetCardIndex = insertionIndexToCardIndex(insertionIndex);
        for (int i = 0; i < providerCards.size(); i++) {
            ProviderCard card = providerCards.get(i);
            card.setDragHover(i == targetCardIndex && card != draggingCard);
        }
    }

    private int insertionIndexToCardIndex(int insertionIndex) {
        if (providerCards.isEmpty()) {
            return -1;
        }
        if (insertionIndex <= 0) {
            return 0;
        }
        if (insertionIndex >= providerCards.size()) {
            return providerCards.size() - 1;
        }
        return insertionIndex;
    }

    private void clearDragPreview() {
        for (ProviderCard card : providerCards) {
            card.setDragHover(false);
        }
    }

    private void refreshProvidersPanel() {
        providersListPanel.removeAll();
        for (int i = 0; i < providerCards.size(); i++) {
            providersListPanel.add(providerCards.get(i));
            if (i < providerCards.size() - 1) {
                providersListPanel.add(Box.createVerticalStrut(8));
            }
        }
        providersListPanel.revalidate();
        providersListPanel.repaint();
    }

    private JobListener uiListener() {
        return new JobListener() {
            @Override
            public void onLog(String message) {
                SwingUtilities.invokeLater(() -> appendLog(message));
            }

            @Override
            public void onStateChanged(JobState state) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("状态: " + state.name());
                    updateButtons(state);
                });
            }

            @Override
            public void onProgress(ProgressSnapshot snapshot) {
                SwingUtilities.invokeLater(() -> {
                    progressBar.setMaximum(Math.max(1, snapshot.totalWords()));
                    progressBar.setValue(snapshot.completedWords());
                    progressBar.setString(String.format(Locale.ROOT,
                            "%d / %d (pending=%d)",
                            snapshot.completedWords(),
                            snapshot.totalWords(),
                            snapshot.pendingWords()));
                    appendLog("[进度] " + snapshot.currentMessage());
                });
            }

            @Override
            public void onError(String message, Throwable throwable) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("[错误] " + message);
                    if (throwable != null) {
                        appendLog(fullStackTrace(throwable));
                    }
                    showError("任务失败: " + message);
                });
            }
        };
    }

    private void chooseInputFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            inputPathField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void chooseOutputFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setSelectedFile(new java.io.File("wordbook.json"));
        chooser.setFileFilter(new FileNameExtensionFilter("Wordbook Output", "json", "csv"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            outputPathField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void chooseConfigDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("\u9009\u62e9\u914d\u7f6e\u6587\u4ef6\u5939");
        Path current = selectedConfigDirectory();
        if (current != null) {
            File currentFile = current.toFile();
            if (currentFile.exists()) {
                chooser.setCurrentDirectory(currentFile);
            }
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            configDirectoryField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void openAdvancedSettingsDialog() {
        AdvancedSettingsState draft = advancedSettings.copy();
        AdvancedSettingsDialog dialog = new AdvancedSettingsDialog(this, draft);
        dialog.setVisible(true);
        if (dialog.isConfirmed()) {
            advancedSettings = dialog.result();
            appendLog("高级设置已更新。");
        }
    }

    private void updateButtons(JobState state) {
        boolean running = state == JobState.RUNNING;
        boolean paused = state == JobState.PAUSED;
        boolean active = running || paused || state == JobState.STOPPING;

        startButton.setEnabled(!active);
        pauseButton.setEnabled(running);
        resumeButton.setEnabled(paused);
        stopButton.setEnabled(running || paused);
        addProviderButton.setEnabled(!active);
        autoFetchModelsAfterTestCheck.setEnabled(!active);
        advancedSettingsButton.setEnabled(!active);
        loadConfigButton.setEnabled(!active);
        configDirectoryField.setEnabled(!active);
        browseConfigDirectoryButton.setEnabled(!active);
        allowNonStandardResponsesCheck.setEnabled(!active);
        autoContinueTruncatedCheck.setEnabled(!active);

        for (ProviderCard card : providerCards) {
            card.setEditable(!active);
        }
    }

    private UiSettings collectUiSettings() {
        UiSettings settings = new UiSettings();
        settings.providersText = "";
        settings.configDirectory = configDirectoryField.getText() == null ? "" : configDirectoryField.getText().trim();
        settings.providers = new ArrayList<>();
        for (ProviderCard card : providerCards) {
            settings.providers.add(card.toUiProviderItem());
        }

        settings.promptText = promptArea.getText();
        settings.inputPath = inputPathField.getText();
        settings.outputPath = outputPathField.getText();
        settings.outputFormat = (String) outputFormatCombo.getSelectedItem();
        settings.sourceLanguage = sourceLangField.getText();
        settings.targetLanguage = targetLangField.getText();
        settings.encoding = (String) encodingCombo.getSelectedItem();
        settings.batchSize = (int) batchSizeSpinner.getValue();
        settings.parallelism = (int) parallelismSpinner.getValue();
        settings.maxRetries = (int) retriesSpinner.getValue();
        settings.timeoutSec = (int) timeoutSpinner.getValue();
        settings.resumeFromCheckpoint = resumeCheck.isSelected();
        settings.clearCheckpointOnSuccess = clearCheckpointCheck.isSelected();
        settings.allowNonStandardResponses = allowNonStandardResponsesCheck.isSelected();
        settings.autoContinueTruncatedOutput = autoContinueTruncatedCheck.isSelected();

        settings.useSystemPromptOverride = advancedSettings.useSystemPromptOverride;
        settings.systemPromptTemplate = advancedSettings.systemPromptTemplate;
        settings.debugMode = advancedSettings.debugMode;
        settings.preRequestHook = copyHookItem(advancedSettings.preRequestHook);
        settings.postResponseHook = copyHookItem(advancedSettings.postResponseHook);
        settings.postParsedHook = copyHookItem(advancedSettings.postParsedHook);
        return settings;
    }

    private UiScriptHookItem copyHookItem(UiScriptHookItem source) {
        UiScriptHookItem ret = new UiScriptHookItem();
        if (source == null) {
            return ret;
        }
        ret.enabled = source.enabled;
        ret.language = source.language;
        ret.scriptPath = source.scriptPath;
        ret.timeoutSec = source.timeoutSec;
        return ret;
    }

    private void applySettings(UiSettings settings) {
        providerCards.clear();

        if (settings.providers != null && !settings.providers.isEmpty()) {
            for (UiProviderItem item : settings.providers) {
                addProviderCard(item, providerCards.size());
            }
        } else if (settings.providersText != null && !settings.providersText.isBlank()) {
            for (ProviderConfig providerConfig : ProviderConfig.parseLines(settings.providersText)) {
                UiProviderItem item = new UiProviderItem();
                item.name = providerConfig.name();
                item.url = providerConfig.endpoint().toString();
                item.apiKey = providerConfig.apiKey();
                item.model = providerConfig.model();
                item.concurrency = providerConfig.maxConcurrency();
                item.quota = providerConfig.quotaLimit();
                item.windowMinutes = providerConfig.windowMinutes();
                addProviderCard(item, providerCards.size());
            }
        } else {
            addProviderCard(defaultProviderItem(), providerCards.size());
        }

        promptArea.setText(settings.promptText == null ? "" : settings.promptText);
        configDirectoryField.setText(settings.configDirectory == null || settings.configDirectory.isBlank()
                ? configStore.defaultSettingsDirectory().toString()
                : settings.configDirectory);
        inputPathField.setText(settings.inputPath == null ? "" : settings.inputPath);
        outputPathField.setText(settings.outputPath == null ? "" : settings.outputPath);
        outputFormatCombo.setSelectedItem(settings.outputFormat == null ? "JSON" : settings.outputFormat);
        sourceLangField.setText(settings.sourceLanguage == null ? "English" : settings.sourceLanguage);
        targetLangField.setText(settings.targetLanguage == null ? "Chinese" : settings.targetLanguage);
        encodingCombo.setSelectedItem(settings.encoding == null ? "UTF-8" : settings.encoding);
        batchSizeSpinner.setValue(settings.batchSize <= 0 ? DEFAULT_BATCH_SIZE : settings.batchSize);
        parallelismSpinner.setValue(Math.max(1, Math.min(8, settings.parallelism <= 0 ? 4 : settings.parallelism)));
        retriesSpinner.setValue(settings.maxRetries <= 0 ? 5 : settings.maxRetries);
        timeoutSpinner.setValue(settings.timeoutSec <= 0 ? DEFAULT_TIMEOUT_SEC : settings.timeoutSec);
        resumeCheck.setSelected(settings.resumeFromCheckpoint);
        clearCheckpointCheck.setSelected(settings.clearCheckpointOnSuccess);
        allowNonStandardResponsesCheck.setSelected(settings.allowNonStandardResponses);
        autoContinueTruncatedCheck.setSelected(settings.autoContinueTruncatedOutput);

        advancedSettings = new AdvancedSettingsState();
        advancedSettings.useSystemPromptOverride = settings.useSystemPromptOverride;
        advancedSettings.systemPromptTemplate = settings.systemPromptTemplate == null ? "" : settings.systemPromptTemplate;
        advancedSettings.debugMode = settings.debugMode;
        advancedSettings.preRequestHook = copyHookItem(settings.preRequestHook);
        advancedSettings.postResponseHook = copyHookItem(settings.postResponseHook);
        advancedSettings.postParsedHook = copyHookItem(settings.postParsedHook);

        if (promptArea.getText().isBlank()) {
            promptArea.setText("请重点保证释义准确、词组常用、例句自然，避免过度生造。支持占位符：{{sourceLanguage}} 与 {{targetLanguage}}。");
        }
    }

    private UiSettings defaultSettings() {
        UiSettings settings = new UiSettings();
        settings.providers = new ArrayList<>();
        settings.providers.add(defaultProviderItem());
        settings.configDirectory = configStore.defaultSettingsDirectory().toString();
        settings.promptText = "请严格按词典标准生成，解释语言使用 {{targetLanguage}}。";
        settings.outputFormat = "JSON";
        settings.encoding = "UTF-8";
        settings.allowNonStandardResponses = true;
        settings.autoContinueTruncatedOutput = false;
        settings.useSystemPromptOverride = false;
        settings.systemPromptTemplate = "";
        settings.debugMode = false;
        settings.preRequestHook = new UiScriptHookItem();
        settings.postResponseHook = new UiScriptHookItem();
        settings.postParsedHook = new UiScriptHookItem();
        return settings;
    }

    private UiProviderItem defaultProviderItem() {
        UiProviderItem item = new UiProviderItem();
        item.name = "ark";
        item.url = "https://ark.cn-beijing.volces.com/api/coding/v1/chat/completions";
        item.apiKey = "YOUR_API_KEY";
        item.model = "doubao-seed-2.0-pro";
        item.concurrency = 8;
        item.quota = 1000;
        item.windowMinutes = 300;
        return item;
    }

    private String requireText(String value, String message) {
        String ret = value == null ? "" : value.trim();
        if (ret.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return ret;
    }

    private String normalizeText(String value, String defaultValue) {
        String ret = value == null ? "" : value.trim();
        return ret.isEmpty() ? defaultValue : ret;
    }

    private Path selectedConfigDirectory() {
        String value = configDirectoryField.getText() == null ? "" : configDirectoryField.getText().trim();
        if (value.isEmpty()) {
            return configStore.defaultSettingsDirectory();
        }
        return Path.of(value);
    }

    private void appendLog(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        logArea.append(line + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private String fullStackTrace(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private void showError(String message) {
        showMessageDialog("提示", message, JOptionPane.ERROR_MESSAGE);
    }

    private void showMessageDialog(String title, String message, int messageType) {
        JTextArea textArea = new JTextArea(message == null ? "" : message, 8, 64);
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setCaretPosition(0);
        textArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(680, 240));
        JOptionPane.showMessageDialog(this, scrollPane, title, messageType);
    }

}
