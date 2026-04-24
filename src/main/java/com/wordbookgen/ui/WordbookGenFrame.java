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
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
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
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final int LOG_PANEL_EXPANDED_HEIGHT = 220;
    private static final int LOG_PANEL_COLLAPSED_HEIGHT = 54;
    private static final Color APP_BACKGROUND = new Color(244, 247, 251);
    private static final Color CARD_BACKGROUND = Color.WHITE;
    private static final Color SOFT_BORDER = new Color(218, 226, 236);

    /**
     * 内置脚本根目录。
     */
    private static final Path BUILTIN_SCRIPTS_DIR = Path.of("examples", "hooks", "builtin")
            .toAbsolutePath()
            .normalize();

    /**
     * 示例脚本根目录（当内置目录不存在时回退）。
     */
    private static final Path EXAMPLE_SCRIPTS_DIR = Path.of("examples", "hooks")
            .toAbsolutePath()
            .normalize();

    private final WordbookJobEngine engine = new WordbookJobEngine();
    private final UiConfigStore configStore = new UiConfigStore();
    private final ProviderConnectivityTester connectivityTester = new ProviderConnectivityTester();
    private final ModelDiscoveryClient modelDiscoveryClient = new ModelDiscoveryClient();

    private final JTextArea promptArea = new JTextArea(4, 80);
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
        setMinimumSize(new Dimension(960, 680));
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
        int width = Math.max(960, Math.min((int) (screen.width * 0.92), 1500));
        int height = Math.max(680, Math.min((int) (screen.height * 0.9), 980));
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
        progressBar.setPreferredSize(new Dimension(380, 26));
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
        container.add(new JPanel(), c);
        return container;
    }

    private JPanel createNaturalPathAndOptionsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        applySectionStyle(panel, "\u8f93\u5165\u8f93\u51fa\u4e0e\u8fd0\u884c\u9009\u9879");

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 8, 6, 8);
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        addPathRow(panel, c, row++, "\u8f93\u5165\u5355\u8bcd\u6587\u4ef6", inputPathField,
                "\u9009\u62e9", this::chooseInputFile);
        addPathRow(panel, c, row++, "\u8f93\u51fa\u6587\u4ef6", outputPathField,
                "\u9009\u62e9", this::chooseOutputFile);
        addPathRow(panel, c, row++, "\u914d\u7f6e\u6587\u4ef6\u5939", configDirectoryField,
                browseConfigDirectoryButton, this::chooseConfigDirectory);

        addLabeledComponent(panel, c, row, 0, "\u8f93\u51fa\u683c\u5f0f", outputFormatCombo, 1);
        addLabeledComponent(panel, c, row++, 2, "\u6587\u4ef6\u7f16\u7801", encodingCombo, 1);
        addLabeledComponent(panel, c, row, 0, "\u6e90\u8bed\u8a00", sourceLangField, 1);
        addLabeledComponent(panel, c, row++, 2, "\u91ca\u4e49\u8bed\u8a00", targetLangField, 1);
        addLabeledComponent(panel, c, row, 0, "\u6279\u5927\u5c0f", batchSizeSpinner, 1);
        addLabeledComponent(panel, c, row++, 2, "\u5e76\u53d1\u6570", parallelismSpinner, 1);
        addLabeledComponent(panel, c, row, 0, "\u91cd\u8bd5\u6b21\u6570", retriesSpinner, 1);
        addLabeledComponent(panel, c, row++, 2, "\u8bf7\u6c42\u8d85\u65f6(\u79d2)", timeoutSpinner, 1);

        JPanel switches = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        switches.setOpaque(false);
        switches.add(resumeCheck);
        switches.add(clearCheckpointCheck);
        switches.add(allowNonStandardResponsesCheck);
        switches.add(autoContinueTruncatedCheck);
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 4;
        c.weightx = 1;
        panel.add(switches, c);
        c.gridwidth = 1;
        return panel;
    }

    private void addPathRow(
            JPanel panel,
            GridBagConstraints c,
            int row,
            String label,
            JTextField field,
            String buttonText,
            Runnable action
    ) {
        addPathRow(panel, c, row, label, field, new JButton(buttonText), action);
    }

    private void addPathRow(
            JPanel panel,
            GridBagConstraints c,
            int row,
            String label,
            JTextField field,
            JButton button,
            Runnable action
    ) {
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.gridwidth = 2;
        c.weightx = 1;
        panel.add(field, c);
        c.gridx = 3;
        c.gridwidth = 1;
        c.weightx = 0;
        button.addActionListener(e -> action.run());
        panel.add(button, c);
    }

    private void addLabeledComponent(
            JPanel panel,
            GridBagConstraints c,
            int row,
            int column,
            String label,
            java.awt.Component component,
            int componentWidth
    ) {
        c.gridy = row;
        c.gridx = column;
        c.gridwidth = 1;
        c.weightx = 0;
        panel.add(new JLabel(label), c);
        c.gridx = column + 1;
        c.gridwidth = componentWidth;
        c.weightx = 1;
        panel.add(component, c);
        c.gridwidth = 1;
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
        scrollPane.setPreferredSize(new Dimension(10, 280));
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

    private JPanel createPathAndOptionsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("基础配置"));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 6, 6, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 0;
        int row = 0;

        c.gridx = 0;
        c.gridy = row;
        panel.add(new JLabel("输入文件路径"), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(inputPathField, c);
        c.gridx = 2;
        c.weightx = 0;
        JButton browseInput = new JButton("浏览");
        browseInput.addActionListener(e -> chooseInputFile());
        panel.add(browseInput, c);

        row++;
        c.gridx = 0;
        c.gridy = row;
        panel.add(new JLabel("输出文件路径"), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(outputPathField, c);
        c.gridx = 2;
        c.weightx = 0;
        JButton browseOutput = new JButton("浏览");
        browseOutput.addActionListener(e -> chooseOutputFile());
        panel.add(browseOutput, c);

        row++;
        c.gridx = 0;
        c.gridy = row;
        panel.add(new JLabel("\u914d\u7f6e\u6587\u4ef6\u5939"), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(configDirectoryField, c);
        c.gridx = 2;
        c.weightx = 0;
        browseConfigDirectoryButton.addActionListener(e -> chooseConfigDirectory());
        panel.add(browseConfigDirectoryButton, c);

        row++;
        c.gridy = row;
        c.gridx = 0;
        panel.add(new JLabel("输出格式"), c);
        c.gridx = 1;
        panel.add(outputFormatCombo, c);
        c.gridx = 2;
        panel.add(new JLabel("编码"), c);
        c.gridx = 3;
        panel.add(encodingCombo, c);

        row++;
        c.gridy = row;
        c.gridx = 0;
        panel.add(new JLabel("源语言"), c);
        c.gridx = 1;
        panel.add(sourceLangField, c);
        c.gridx = 2;
        panel.add(new JLabel("目标解释语言"), c);
        c.gridx = 3;
        panel.add(targetLangField, c);

        row++;
        c.gridy = row;
        c.gridx = 0;
        panel.add(new JLabel("批大小"), c);
        c.gridx = 1;
        panel.add(batchSizeSpinner, c);
        c.gridx = 2;
        panel.add(new JLabel("并发度(<=8)"), c);
        c.gridx = 3;
        panel.add(parallelismSpinner, c);

        row++;
        c.gridy = row;
        c.gridx = 0;
        panel.add(new JLabel("最大重试"), c);
        c.gridx = 1;
        panel.add(retriesSpinner, c);
        c.gridx = 2;
        panel.add(new JLabel("请求超时(秒)"), c);
        c.gridx = 3;
        panel.add(timeoutSpinner, c);

        row++;
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 2;
        panel.add(resumeCheck, c);
        c.gridx = 2;
        c.gridwidth = 2;
        panel.add(clearCheckpointCheck, c);
        row++;
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 4;
        panel.add(allowNonStandardResponsesCheck, c);
        c.gridwidth = 1;
        return panel;
    }

    private JPanel createProviderPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Provider 配置（可折叠卡片 / 拖拽排序 / 复制）"));

        JPanel topBar = new JPanel(new BorderLayout());
        topBar.add(new JLabel("每个 Provider 独立配置，支持连接测试与自动发现模型列表"), BorderLayout.WEST);
        JPanel rightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightActions.add(autoFetchModelsAfterTestCheck);
        rightActions.add(addProviderButton);
        topBar.add(rightActions, BorderLayout.EAST);
        panel.add(topBar, BorderLayout.NORTH);

        providersListPanel.setLayout(new BoxLayout(providersListPanel, BoxLayout.Y_AXIS));
        JScrollPane scrollPane = new JScrollPane(providersListPanel);
        scrollPane.setPreferredSize(new Dimension(10, 280));
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createPromptPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("用户提示词补充"));
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        panel.add(new JScrollPane(promptArea), BorderLayout.CENTER);
        return panel;
    }

    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("控制台日志"));
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(10, 220));
        return panel;
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

    /**
     * 高级设置状态。
     */
    private static class AdvancedSettingsState {
        boolean useSystemPromptOverride;
        String systemPromptTemplate;
        boolean debugMode;
        UiScriptHookItem preRequestHook;
        UiScriptHookItem postResponseHook;
        UiScriptHookItem postParsedHook;

        static AdvancedSettingsState defaultState() {
            AdvancedSettingsState state = new AdvancedSettingsState();
            state.useSystemPromptOverride = false;
            state.systemPromptTemplate = "";
            state.debugMode = false;
            state.preRequestHook = new UiScriptHookItem();
            state.postResponseHook = new UiScriptHookItem();
            state.postParsedHook = new UiScriptHookItem();
            return state;
        }

        AdvancedSettingsState copy() {
            AdvancedSettingsState ret = new AdvancedSettingsState();
            ret.useSystemPromptOverride = this.useSystemPromptOverride;
            ret.systemPromptTemplate = this.systemPromptTemplate == null ? "" : this.systemPromptTemplate;
            ret.debugMode = this.debugMode;
            ret.preRequestHook = copyHook(this.preRequestHook);
            ret.postResponseHook = copyHook(this.postResponseHook);
            ret.postParsedHook = copyHook(this.postParsedHook);
            return ret;
        }

        private UiScriptHookItem copyHook(UiScriptHookItem item) {
            UiScriptHookItem ret = new UiScriptHookItem();
            if (item == null) {
                return ret;
            }
            ret.enabled = item.enabled;
            ret.language = item.language;
            ret.scriptPath = item.scriptPath;
            ret.timeoutSec = item.timeoutSec;
            return ret;
        }
    }

    /**
     * 高级设置弹窗。
     */
    private static class AdvancedSettingsDialog extends JDialog {

        private final JCheckBox overrideCheck = new JCheckBox("启用系统提示词覆盖");
        private final JCheckBox debugCheck = new JCheckBox("调试模式：输出完整模型返回（日志会明显变长）");
        private final JTextArea systemPromptArea = new JTextArea(8, 80);

        private final HookPanel preRequestPanel = new HookPanel("请求前 Hook（PRE_REQUEST）");
        private final HookPanel postResponsePanel = new HookPanel("响应返回 Hook（POST_RESPONSE）");
        private final HookPanel postParsedPanel = new HookPanel("结果解析后 Hook（POST_PARSED）");

        private final AdvancedSettingsState result;
        private boolean confirmed = false;

        AdvancedSettingsDialog(JFrame owner, AdvancedSettingsState current) {
            super(owner, "高级设置", true);
            this.result = current.copy();
            setSize(980, 760);
            setLocationRelativeTo(owner);
            setLayout(new BorderLayout(8, 8));

            JPanel root = new JPanel();
            root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));

            JPanel systemPromptPanel = new JPanel(new BorderLayout());
            systemPromptPanel.setBorder(BorderFactory.createTitledBorder("系统提示词覆盖"));
            overrideCheck.setSelected(current.useSystemPromptOverride);
            debugCheck.setSelected(current.debugMode);
            systemPromptArea.setText(current.systemPromptTemplate == null ? "" : current.systemPromptTemplate);
            systemPromptArea.setLineWrap(true);
            systemPromptArea.setWrapStyleWord(true);
            String help = "可用占位符：{{sourceLanguage}} {{targetLanguage}} {{requiredFields}}";
            JPanel systemTop = new JPanel();
            systemTop.setLayout(new BoxLayout(systemTop, BoxLayout.Y_AXIS));
            systemTop.add(overrideCheck);
            systemTop.add(debugCheck);
            systemPromptPanel.add(systemTop, BorderLayout.NORTH);
            systemPromptPanel.add(new JScrollPane(systemPromptArea), BorderLayout.CENTER);
            systemPromptPanel.add(new JLabel(help), BorderLayout.SOUTH);
            root.add(systemPromptPanel);
            root.add(Box.createVerticalStrut(8));

            preRequestPanel.fill(current.preRequestHook);
            postResponsePanel.fill(current.postResponseHook);
            postParsedPanel.fill(current.postParsedHook);

            root.add(preRequestPanel);
            root.add(Box.createVerticalStrut(6));
            root.add(postResponsePanel);
            root.add(Box.createVerticalStrut(6));
            root.add(postParsedPanel);

            add(new JScrollPane(root), BorderLayout.CENTER);

            JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton okButton = new JButton("确定");
            JButton cancelButton = new JButton("取消");
            footer.add(okButton);
            footer.add(cancelButton);
            add(footer, BorderLayout.SOUTH);

            okButton.addActionListener(e -> {
                result.useSystemPromptOverride = overrideCheck.isSelected();
                result.debugMode = debugCheck.isSelected();
                result.systemPromptTemplate = systemPromptArea.getText();
                result.preRequestHook = preRequestPanel.toItem();
                result.postResponseHook = postResponsePanel.toItem();
                result.postParsedHook = postParsedPanel.toItem();
                confirmed = true;
                dispose();
            });
            cancelButton.addActionListener(e -> dispose());
        }

        boolean isConfirmed() {
            return confirmed;
        }

        AdvancedSettingsState result() {
            return result;
        }
    }

    /**
     * 单个 Hook 的编辑区域。
     */
    private static class HookPanel extends JPanel {

        private final JCheckBox enabledCheck = new JCheckBox("启用");
        private final JComboBox<String> languageCombo = new JComboBox<>(new String[]{"PYTHON", "JAVASCRIPT", "LUA", "JAVA"});
        private final JTextField pathField = new JTextField();
        private final JSpinner timeoutSpinner = new JSpinner(new SpinnerNumberModel(30, 1, 600, 1));

        HookPanel(String title) {
            setLayout(new GridBagLayout());
            setBorder(BorderFactory.createTitledBorder(title));

            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(4, 6, 4, 6);
            c.fill = GridBagConstraints.HORIZONTAL;

            c.gridx = 0;
            c.gridy = 0;
            add(enabledCheck, c);
            c.gridx = 1;
            add(new JLabel("语言"), c);
            c.gridx = 2;
            add(languageCombo, c);
            c.gridx = 3;
            add(new JLabel("超时(秒)"), c);
            c.gridx = 4;
            add(timeoutSpinner, c);

            c.gridx = 0;
            c.gridy = 1;
            add(new JLabel("脚本路径"), c);
            c.gridx = 1;
            c.gridwidth = 3;
            c.weightx = 1;
            add(pathField, c);
            c.gridx = 4;
            c.gridwidth = 1;
            c.weightx = 0;
            JButton browseButton = new JButton("浏览");
            browseButton.addActionListener(e -> {
                JFileChooser chooser = new JFileChooser();
                chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
                File defaultDir = resolveScriptBrowseDirectory(pathField.getText());
                if (defaultDir != null && defaultDir.exists()) {
                    chooser.setCurrentDirectory(defaultDir);
                }
                if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                    pathField.setText(chooser.getSelectedFile().getAbsolutePath());
                }
            });
            add(browseButton, c);
        }

        /**
         * 脚本浏览的默认目录策略：
         * 1. 优先使用当前输入路径（若存在）；
         * 2. 否则使用内置脚本目录；
         * 3. 再回退到示例脚本目录；
         * 4. 最后回退到程序当前目录。
         */
        private File resolveScriptBrowseDirectory(String currentPath) {
            if (currentPath != null && !currentPath.isBlank()) {
                File current = new File(currentPath.trim());
                if (current.exists()) {
                    if (current.isDirectory()) {
                        return current;
                    }
                    File parent = current.getParentFile();
                    if (parent != null && parent.exists()) {
                        return parent;
                    }
                }
            }

            File builtinDir = BUILTIN_SCRIPTS_DIR.toFile();
            if (builtinDir.exists()) {
                return builtinDir;
            }

            File exampleDir = EXAMPLE_SCRIPTS_DIR.toFile();
            if (exampleDir.exists()) {
                return exampleDir;
            }

            return new File(".").getAbsoluteFile();
        }

        void fill(UiScriptHookItem item) {
            if (item == null) {
                return;
            }
            enabledCheck.setSelected(item.enabled);
            languageCombo.setSelectedItem(item.language == null ? "JAVASCRIPT" : item.language.toUpperCase(Locale.ROOT));
            pathField.setText(item.scriptPath == null ? "" : item.scriptPath);
            timeoutSpinner.setValue(item.timeoutSec <= 0 ? 30 : item.timeoutSec);
        }

        UiScriptHookItem toItem() {
            UiScriptHookItem item = new UiScriptHookItem();
            item.enabled = enabledCheck.isSelected();
            item.language = (String) languageCombo.getSelectedItem();
            item.scriptPath = pathField.getText() == null ? "" : pathField.getText().trim();
            item.timeoutSec = (int) timeoutSpinner.getValue();
            return item;
        }
    }

    /**
     * Provider 折叠卡片。
     */
    private static class ProviderCard extends JPanel {

        private static final Color BORDER_NORMAL = new Color(215, 223, 233);
        private static final Color BORDER_HOVER = new Color(74, 127, 205);
        private static final Color HEADER_BG = new Color(246, 249, 253);

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

            dragHandle.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            dragHandle.setForeground(new Color(68, 85, 102));
            summaryLabel.setForeground(new Color(102, 110, 125));
            applyNaturalText();

            JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
            header.setBackground(HEADER_BG);
            header.add(dragHandle);
            header.add(titleLabel);
            header.add(summaryLabel);
            header.add(collapseButton);
            header.add(copyButton);
            header.add(deleteButton);
            header.add(testButton);
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
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(4, 6, 4, 6);
            c.fill = GridBagConstraints.HORIZONTAL;

            int row = 0;
            c.gridx = 0;
            c.gridy = row;
            c.weightx = 0;
            bodyPanel.add(new JLabel("name"), c);
            c.gridx = 1;
            c.weightx = 0.25;
            bodyPanel.add(nameField, c);
            c.gridx = 2;
            c.weightx = 0;
            bodyPanel.add(new JLabel("url"), c);
            c.gridx = 3;
            c.weightx = 0.75;
            bodyPanel.add(urlField, c);

            row++;
            c.gridy = row;
            c.gridx = 0;
            c.weightx = 0;
            bodyPanel.add(new JLabel("apiKey"), c);
            c.gridx = 1;
            c.weightx = 0.5;
            bodyPanel.add(apiKeyField, c);
            c.gridx = 2;
            c.weightx = 0;
            bodyPanel.add(new JLabel("model"), c);
            c.gridx = 3;
            c.weightx = 0.5;

            JPanel modelPanel = new JPanel(new BorderLayout(6, 0));
            modelPanel.add(modelCombo, BorderLayout.CENTER);
            modelPanel.add(fetchModelsButton, BorderLayout.EAST);
            bodyPanel.add(modelPanel, c);

            row++;
            c.gridy = row;
            c.gridx = 0;
            c.weightx = 0;
            bodyPanel.add(new JLabel("concurrency"), c);
            c.gridx = 1;
            c.weightx = 0.2;
            bodyPanel.add(concurrencySpinner, c);
            c.gridx = 2;
            c.weightx = 0;
            bodyPanel.add(new JLabel("quota / window(min)"), c);
            c.gridx = 3;
            c.weightx = 0.8;

            JPanel right = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            right.add(quotaSpinner);
            right.add(windowSpinner);
            bodyPanel.add(right, c);
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
            titleLabel.setText(name.isBlank() ? "Provider" : name);
            summaryLabel.setText("[" + (model.isBlank() ? "no-model" : model) + "] " + trimMiddle(url, 36));
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
}
